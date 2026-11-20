#pragma once

#include "freertos/FreeRTOS.h"
#include "freertos/semphr.h"
#include "message.h"
#include "node_event.h"

typedef struct {
    const char *broker_uri;
    const char *password;
    const node_ids_t *ids;
    event_outbox_t *outbox;
    SemaphoreHandle_t lock;  // guards the outbox, shared with the measuring task
} mqtt_link_config_t;

/**
 * Connects to the broker and publishes the outbox in order, one message at a time, removing each only after its
 * PUBACK. Publishes the node's status (online, and offline as the last will). Start it once the clock is set.
 */
void mqtt_link_start(const mqtt_link_config_t *config);

/** Wakes the publisher after a new message went into the outbox. */
void mqtt_link_notify(void);
