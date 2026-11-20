#include "mqtt_link.h"

#include <stdbool.h>
#include <sys/time.h>

#include "esp_log.h"
#include "esp_timer.h"
#include "freertos/task.h"
#include "mqtt_client.h"
#include "wall_clock.h"

#define TOPIC_SIZE 128
#define PAYLOAD_SIZE 512
#define IDLE_WAKEUP_MS 2000
// a PUBACK that has not come after this long is assumed lost: the message goes again, the platform drops the copy
#define ACK_TIMEOUT_MS 15000

static const char *TAG = "mqtt";

static mqtt_link_config_t cfg;
static esp_mqtt_client_handle_t client;
static TaskHandle_t publisher;
static volatile bool connected;
static char status_topic[TOPIC_SIZE];
static char will[160];

static int64_t uptime_ms(void)
{
    return esp_timer_get_time() / 1000;
}

static int64_t now_ms(void)
{
    if (clock_now() == 0) {
        return 0;
    }
    struct timeval tv;
    gettimeofday(&tv, NULL);
    return (int64_t)tv.tv_sec * 1000 + tv.tv_usec / 1000;
}

void mqtt_link_notify(void)
{
    if (publisher != NULL) {
        xTaskNotifyGive(publisher);
    }
}

static void publish_status(const char *status)
{
    char payload[160];
    int len = message_encode_status(payload, sizeof(payload), cfg.ids, status, now_ms());
    if (len > 0) {
        esp_mqtt_client_publish(client, status_topic, payload, len, 1, 1);
    }
}

static void on_event(void *arg, esp_event_base_t base, int32_t id, void *data)
{
    esp_mqtt_event_handle_t event = data;
    switch ((esp_mqtt_event_id_t)id) {
    case MQTT_EVENT_CONNECTED:
        ESP_LOGI(TAG, "connected to the broker");
        connected = true;
        publish_status("online");
        mqtt_link_notify();
        break;
    case MQTT_EVENT_DISCONNECTED:
        ESP_LOGW(TAG, "disconnected from the broker");
        connected = false;
        xSemaphoreTake(cfg.lock, portMAX_DELAY);
        event_outbox_requeue(cfg.outbox);
        xSemaphoreGive(cfg.lock);
        break;
    case MQTT_EVENT_PUBLISHED:
        xSemaphoreTake(cfg.lock, portMAX_DELAY);
        event_outbox_ack(cfg.outbox, event->msg_id);
        xSemaphoreGive(cfg.lock);
        mqtt_link_notify();
        break;
    case MQTT_EVENT_ERROR:
        if (event->error_handle->error_type == MQTT_ERROR_TYPE_CONNECTION_REFUSED) {
            ESP_LOGE(TAG, "the broker refused the login (code %d): check the password and the ACL",
                     event->error_handle->connect_return_code);
        } else {
            ESP_LOGE(TAG, "mqtt error, type %d", event->error_handle->error_type);
        }
        break;
    default:
        break;
    }
}

/**
 * Sends the oldest message of the outbox when nothing is in flight. One at a time keeps them in order whatever
 * happens to the connection; at one message per state change plus one every 10 s, that is plenty.
 */
static void publish_task(void *arg)
{
    char topic[TOPIC_SIZE];
    char payload[PAYLOAD_SIZE];
    int64_t sent_at = 0;
    for (;;) {
        ulTaskNotifyTake(pdTRUE, pdMS_TO_TICKS(IDLE_WAKEUP_MS));
        if (!connected) {
            continue;
        }
        xSemaphoreTake(cfg.lock, portMAX_DELAY);
        node_event_t *front = event_outbox_front(cfg.outbox);
        if (front != NULL && front->msg_id != 0 && uptime_ms() - sent_at > ACK_TIMEOUT_MS) {
            ESP_LOGW(TAG, "no PUBACK for seq %llu, sending it again", (unsigned long long)front->seq);
            event_outbox_requeue(cfg.outbox);
        }
        if (front == NULL || front->msg_id != 0) {
            xSemaphoreGive(cfg.lock);
            continue;
        }
        node_event_t event = *front;
        int64_t at = event_time_ms(&event, now_ms(), uptime_ms());
        int len = at == 0 ? -2 : message_encode(payload, sizeof(payload), cfg.ids, &event, at);
        if (len == -1 || message_topic(topic, sizeof(topic), cfg.ids, event.kind) < 0) {
            ESP_LOGE(TAG, "cannot encode seq %llu, dropping it", (unsigned long long)event.seq);
            event_outbox_drop_front(cfg.outbox);
            xSemaphoreGive(cfg.lock);
            mqtt_link_notify();
            continue;
        }
        xSemaphoreGive(cfg.lock);
        if (len < 0) {
            continue; // clock not set
        }

        // the lock is not held while enqueueing: the mqtt task takes it in the PUBACK handler
        int msg_id = esp_mqtt_client_enqueue(client, topic, payload, len, 1, 0, true);
        if (msg_id <= 0) {
            continue;
        }
        xSemaphoreTake(cfg.lock, portMAX_DELAY);
        front = event_outbox_front(cfg.outbox);
        // the outbox may have dropped it meanwhile (full); mark only the message just sent
        if (front != NULL && front->seq == event.seq && front->msg_id == 0) {
            front->msg_id = msg_id;
            sent_at = uptime_ms();
        }
        xSemaphoreGive(cfg.lock);
    }
}

void mqtt_link_start(const mqtt_link_config_t *config)
{
    cfg = *config;
    message_status_topic(status_topic, sizeof(status_topic), cfg.ids);
    // the will is written now, so its time is the connection time; the platform keeps the newest status by time
    message_encode_status(will, sizeof(will), cfg.ids, "offline", now_ms());

    const esp_mqtt_client_config_t mqtt_config = {
        .broker.address.uri = cfg.broker_uri,
        .credentials = {
            .username = cfg.ids->gateway_id,
            .client_id = cfg.ids->gateway_id,
            .authentication.password = cfg.password,
        },
        .session = {
            .keepalive = 30,
            .last_will = {
                .topic = status_topic,
                .msg = will,
                .qos = 1,
                .retain = 1,
            },
        },
        .network.reconnect_timeout_ms = 5000,
    };
    client = esp_mqtt_client_init(&mqtt_config);
    ESP_ERROR_CHECK(esp_mqtt_client_register_event(client, ESP_EVENT_ANY_ID, on_event, NULL));
    ESP_ERROR_CHECK(esp_mqtt_client_start(client));
    xTaskCreate(publish_task, "publish", 6144, NULL, 5, &publisher);
}
