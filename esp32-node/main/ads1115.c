#include "ads1115.h"

#include "driver/gpio.h"
#include "driver/i2c_master.h"
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"

#define REG_CONVERSION 0x00
#define REG_CONFIG 0x01
#define REG_LO_THRESH 0x02
#define REG_HI_THRESH 0x03

// MUX 000 (AIN0 - AIN1), MODE 0 (continuous), DR 111 (860 SPS), comparator asserting after one conversion
#define CONFIG_BASE 0x00E0
#define CONFIG_PGA_SHIFT 9
#define I2C_TIMEOUT_MS 20

static ads1115_config_t cfg;
static i2c_master_dev_handle_t device;
static TaskHandle_t reader;

static void IRAM_ATTR on_ready(void *arg)
{
    BaseType_t woken = pdFALSE;
    vTaskNotifyGiveFromISR(reader, &woken);
    portYIELD_FROM_ISR(woken);
}

static esp_err_t write_register(uint8_t reg, uint16_t value)
{
    uint8_t buf[3] = { reg, (uint8_t)(value >> 8), (uint8_t)value };
    return i2c_master_transmit(device, buf, sizeof(buf), I2C_TIMEOUT_MS);
}

esp_err_t ads1115_configure(void)
{
    // Hi_thresh with its top bit set and Lo_thresh with it clear turn ALERT/RDY into a conversion-ready pulse
    esp_err_t err = write_register(REG_HI_THRESH, 0x8000);
    if (err == ESP_OK) {
        err = write_register(REG_LO_THRESH, 0x0000);
    }
    if (err == ESP_OK) {
        err = write_register(REG_CONFIG, CONFIG_BASE | ((uint16_t)cfg.range << CONFIG_PGA_SHIFT));
    }
    return err;
}

esp_err_t ads1115_start(const ads1115_config_t *config)
{
    cfg = *config;
    reader = xTaskGetCurrentTaskHandle();

    i2c_master_bus_config_t bus_config = {
        .i2c_port = -1,
        .sda_io_num = cfg.sda_gpio,
        .scl_io_num = cfg.scl_gpio,
        .clk_source = I2C_CLK_SRC_DEFAULT,
        .glitch_ignore_cnt = 7,
        // the breakout boards carry their own pull-ups; the internal ones only help with short wires
        .flags.enable_internal_pullup = true,
    };
    i2c_master_bus_handle_t bus;
    esp_err_t err = i2c_new_master_bus(&bus_config, &bus);
    if (err != ESP_OK) {
        return err;
    }
    i2c_device_config_t device_config = {
        .dev_addr_length = I2C_ADDR_BIT_LEN_7,
        .device_address = cfg.address,
        .scl_speed_hz = 400000,
    };
    err = i2c_master_bus_add_device(bus, &device_config, &device);
    if (err != ESP_OK) {
        return err;
    }

    // ALERT/RDY is open drain, active low: a short low pulse at the end of each conversion
    gpio_config_t rdy = {
        .pin_bit_mask = 1ULL << cfg.rdy_gpio,
        .mode = GPIO_MODE_INPUT,
        .pull_up_en = GPIO_PULLUP_ENABLE,
        .intr_type = GPIO_INTR_NEGEDGE,
    };
    err = gpio_config(&rdy);
    if (err == ESP_OK) {
        err = gpio_install_isr_service(0);
    }
    if (err == ESP_OK) {
        err = gpio_isr_handler_add(cfg.rdy_gpio, on_ready, NULL);
    }
    if (err != ESP_OK) {
        return err;
    }
    return ads1115_configure();
}

double ads1115_volts_per_lsb(void)
{
    double full_scale = cfg.range == ADS1115_RANGE_512 ? 0.512 : cfg.range == ADS1115_RANGE_1024 ? 1.024 : 2.048;
    return full_scale / 32768.0;
}

esp_err_t ads1115_read_next(int16_t *raw, uint32_t timeout_ms)
{
    if (ulTaskNotifyTake(pdTRUE, pdMS_TO_TICKS(timeout_ms)) == 0) {
        return ESP_ERR_TIMEOUT;
    }
    uint8_t reg = REG_CONVERSION;
    uint8_t value[2];
    esp_err_t err = i2c_master_transmit_receive(device, &reg, 1, value, sizeof(value), I2C_TIMEOUT_MS);
    if (err == ESP_OK) {
        *raw = (int16_t)((value[0] << 8) | value[1]);
    }
    return err;
}
