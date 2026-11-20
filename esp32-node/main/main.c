#include <inttypes.h>
#include <stdbool.h>
#include <sys/time.h>

#include "ads1115.h"
#include "current.h"
#include "driver/gpio.h"
#include "esp_event.h"
#include "esp_log.h"
#include "esp_netif.h"
#include "esp_netif_sntp.h"
#include "esp_timer.h"
#include "freertos/FreeRTOS.h"
#include "freertos/queue.h"
#include "freertos/semphr.h"
#include "freertos/task.h"
#include "machine_state.h"
#include "message.h"
#include "mqtt_link.h"
#include "node_event.h"
#include "nvs.h"
#include "nvs_flash.h"
#include "wall_clock.h"
#include "wifi.h"

static const char *TAG = "node";

#define SECOND_US 1000000
// fewer conversions than this in a second means the RDY pulses are missing or the bus is failing
#define MIN_SAMPLES_PER_SECOND 600

typedef struct {
    bool valid;
    double amps;
} second_t;

static const node_ids_t ids = {
    .prefix = CONFIG_MT_TOPIC_PREFIX,
    .site = CONFIG_MT_SITE,
    .gateway_id = CONFIG_MT_GATEWAY_ID,
    .machine_id = CONFIG_MT_MACHINE_ID,
    .analog = CONFIG_MT_ANALOG_NAME,
};

static node_event_t outbox_storage[CONFIG_MT_OUTBOX_CAPACITY];
static event_outbox_t outbox;
static SemaphoreHandle_t outbox_lock;
static QueueHandle_t seconds;
static seq_gen_t seq;
static nvs_handle_t nvs;

static void init_nvs(void)
{
    esp_err_t err = nvs_flash_init();
    if (err == ESP_ERR_NVS_NO_FREE_PAGES || err == ESP_ERR_NVS_NEW_VERSION_FOUND) {
        ESP_ERROR_CHECK(nvs_flash_erase());
        err = nvs_flash_init();
    }
    ESP_ERROR_CHECK(err);
    ESP_ERROR_CHECK(nvs_open("node", NVS_READWRITE, &nvs));
}

static void save_seq_block(void)
{
    ESP_ERROR_CHECK(nvs_set_u32(nvs, "seq_block", seq.block));
    ESP_ERROR_CHECK(nvs_commit(nvs));
}

static void init_seq(void)
{
    uint32_t stored = 0;
    nvs_get_u32(nvs, "seq_block", &stored); // stays 0 on the first boot
    seq_init(&seq, stored);
    // saved before the first number is used, so a reboot right after can never hand out the same numbers
    save_seq_block();
    ESP_LOGI(TAG, "sequence block %" PRIu32, seq.block);
}

static int64_t wall_ms(void)
{
    if (clock_now() == 0) {
        return 0;
    }
    struct timeval tv;
    gettimeofday(&tv, NULL);
    return (int64_t)tv.tv_sec * 1000 + tv.tv_usec / 1000;
}

static ads1115_range_t range(void)
{
#if CONFIG_MT_ADS1115_RANGE_512
    return ADS1115_RANGE_512;
#elif CONFIG_MT_ADS1115_RANGE_1024
    return ADS1115_RANGE_1024;
#else
    return ADS1115_RANGE_2048;
#endif
}

/**
 * Reads every conversion of the ADS1115 (860 per second) and hands one RMS current per second to the node task.
 * It runs on the core that does not run Wi-Fi, at a high priority, so no sample is missed while the radio is busy.
 */
static void sample_task(void *arg)
{
    const ads1115_config_t config = {
        .sda_gpio = CONFIG_MT_I2C_SDA_GPIO,
        .scl_gpio = CONFIG_MT_I2C_SCL_GPIO,
        .rdy_gpio = CONFIG_MT_ADS1115_RDY_GPIO,
        .address = CONFIG_MT_ADS1115_ADDRESS,
        .range = range(),
    };
    esp_err_t err = ads1115_start(&config);
    if (err != ESP_OK) {
        ESP_LOGE(TAG, "ADS1115 not found at 0x%02x (%s): check SDA/SCL and the power of the board", config.address,
                 esp_err_to_name(err));
    }
    double volts_per_lsb = ads1115_volts_per_lsb();
    rms_acc_t acc;
    bool reconfigure = err != ESP_OK;
    for (;;) {
        int64_t start = esp_timer_get_time();
        if (reconfigure) {
            reconfigure = ads1115_configure() != ESP_OK;
        }
        rms_reset(&acc);
        bool ok = !reconfigure;
        while (ok && esp_timer_get_time() - start < SECOND_US) {
            int16_t raw;
            if (ads1115_read_next(&raw, 50) != ESP_OK) {
                ok = false;
                break;
            }
            rms_add(&acc, raw);
        }
        if (ok && acc.n < MIN_SAMPLES_PER_SECOND) {
            ok = false;
        }
        if (!ok) {
            ESP_LOGW(TAG, "no conversions from the ADS1115 (%" PRIu32 " this second): check the ALERT/RDY wire on GPIO %d",
                     acc.n, config.rdy_gpio);
            reconfigure = true;
            int64_t left = SECOND_US - (esp_timer_get_time() - start);
            if (left > 0) {
                vTaskDelay(pdMS_TO_TICKS(left / 1000) + 1);
            }
        }
        second_t s = {
            .valid = ok,
            .amps = ok ? current_amps(rms_volts(&acc, volts_per_lsb), CONFIG_MT_CT_AMPS_PER_VOLT, CONFIG_MT_CT_TURNS) : 0,
        };
        xQueueSend(seconds, &s, 0);
    }
}

static void push(node_event_t *event)
{
    bool save = false;
    event->uptime_ms = esp_timer_get_time() / 1000;
    event->unix_ms = wall_ms();
    xSemaphoreTake(outbox_lock, portMAX_DELAY);
    event->seq = seq_next(&seq, &save);
    if (save) {
        save_seq_block();
    }
    event_outbox_push(&outbox, event);
    xSemaphoreGive(outbox_lock);
    mqtt_link_notify();
}

static void set_led(machine_state_t state)
{
#if CONFIG_MT_LED_GPIO >= 0
    gpio_set_level(CONFIG_MT_LED_GPIO, state == MS_RUNNING || state == MS_FAULT);
#endif
}

/**
 * Turns the currents into states and telemetry windows and stores them in the outbox. It never touches the
 * network, so a dead access point does not delay a single measurement.
 */
static void node_task(void *arg)
{
    const detector_config_t config = {
        .run_ma = CONFIG_MT_RUN_THRESHOLD_MA,
        .stop_ma = CONFIG_MT_STOP_THRESHOLD_MA,
        .overcurrent_ma = CONFIG_MT_OVERCURRENT_MA,
        .run_seconds = CONFIG_MT_RUN_SECONDS,
        .stop_seconds = CONFIG_MT_STOP_SECONDS,
        .fault_code = CONFIG_MT_OVERCURRENT_FAULT_CODE,
    };
    detector_t detector;
    detector_init(&detector, &config);
    analog_window_t window;
    window_reset(&window);
    uint32_t elapsed = 0;

    for (;;) {
        second_t s;
        if (xQueueReceive(seconds, &s, pdMS_TO_TICKS(3000)) != pdTRUE) {
            s.valid = false; // the sampling task is stuck
        }
        machine_state_t previous = detector.state;
        int previous_code = detector.fault_code;
        if (detector_update(&detector, s.valid, s.amps)) {
            ESP_LOGI(TAG, "%s -> %s", previous == MS_UNKNOWN ? "start" : machine_state_name(previous),
                     machine_state_name(detector.state));
            node_event_t event = {
                .kind = EV_STATE,
                .state = detector.state,
                .fault_code = detector.fault_code,
                .previous_state = previous,
                .previous_fault_code = previous_code,
            };
            push(&event);
            set_led(detector.state);
        }
        if (s.valid) {
            window_add(&window, s.amps);
#if CONFIG_MT_LOG_CURRENT
            ESP_LOGI(TAG, "%.3f A, %s, outbox %u", s.amps, machine_state_name(detector.state), (unsigned)outbox.count);
#endif
        }
        if (++elapsed >= CONFIG_MT_TELEMETRY_SECONDS) {
            node_event_t event = {
                .kind = EV_TELEMETRY,
                .state = detector.state,
                .fault_code = detector.fault_code,
                .window_s = elapsed,
            };
            event.has_current = window_stats(&window, &event.current);
            push(&event);
            window_reset(&window);
            elapsed = 0;
        }
    }
}

void app_main(void)
{
    if (!node_ids_valid(&ids)) {
        ESP_LOGE(TAG, "invalid ids in menuconfig: use 1 to 32 letters, digits, '-', '_' or '.'");
        return;
    }
    init_nvs();
    init_seq();
    outbox_lock = xSemaphoreCreateMutex();
    event_outbox_init(&outbox, outbox_storage, CONFIG_MT_OUTBOX_CAPACITY);
    seconds = xQueueCreate(8, sizeof(second_t));

#if CONFIG_MT_LED_GPIO >= 0
    gpio_reset_pin(CONFIG_MT_LED_GPIO);
    gpio_set_direction(CONFIG_MT_LED_GPIO, GPIO_MODE_OUTPUT);
    gpio_set_level(CONFIG_MT_LED_GPIO, 0);
#endif

    ESP_LOGI(TAG, "gateway %s, machine %s at site %s", ids.gateway_id, ids.machine_id, ids.site);
    // measuring starts before the network: a stop while Wi-Fi comes up is still recorded, dated from the uptime
    xTaskCreatePinnedToCore(sample_task, "sample", 4096, NULL, 10, NULL, 1);
    xTaskCreate(node_task, "node", 4096, NULL, 6, NULL);

    ESP_ERROR_CHECK(esp_netif_init());
    ESP_ERROR_CHECK(esp_event_loop_create_default());
    wifi_start(CONFIG_MT_WIFI_SSID, CONFIG_MT_WIFI_PASSWORD);

    esp_sntp_config_t sntp = ESP_NETIF_SNTP_DEFAULT_CONFIG(CONFIG_MT_NTP_SERVER);
    ESP_ERROR_CHECK(esp_netif_sntp_init(&sntp));
    while (clock_now() == 0) {
        ESP_LOGI(TAG, "waiting for the clock (NTP %s)", CONFIG_MT_NTP_SERVER);
        esp_netif_sntp_sync_wait(pdMS_TO_TICKS(10000));
    }
    ESP_LOGI(TAG, "clock set");

    const mqtt_link_config_t link = {
        .broker_uri = CONFIG_MT_MQTT_BROKER_URI,
        .password = CONFIG_MT_MQTT_PASSWORD,
        .ids = &ids,
        .outbox = &outbox,
        .lock = outbox_lock,
    };
    mqtt_link_start(&link);
}
