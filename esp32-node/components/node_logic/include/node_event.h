#pragma once

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

#include "machine_state.h"

#ifdef __cplusplus
extern "C" {
#endif

typedef struct {
    double avg;
    double min;
    double max;
    double last;
    uint32_t samples;
} analog_stats_t;

/** Min, max, mean and last of the per-second currents of one telemetry window. */
typedef struct {
    double sum;
    double min;
    double max;
    double last;
    uint32_t n;
} analog_window_t;

void window_reset(analog_window_t *w);

void window_add(analog_window_t *w, double value);

/** False when the window got no value (the ADC was unreadable the whole time). */
bool window_stats(const analog_window_t *w, analog_stats_t *out);

typedef enum {
    EV_STATE,
    EV_TELEMETRY,
} event_kind_t;

/** One message waiting to be published. */
typedef struct {
    event_kind_t kind;
    uint64_t seq;
    int64_t uptime_ms;  // when it happened, since boot
    int64_t unix_ms;    // when it happened, wall clock; 0 if the clock was not set yet
    machine_state_t state;
    int fault_code;
    machine_state_t previous_state;  // EV_STATE only; MS_UNKNOWN for the first state after boot
    int previous_fault_code;
    double window_s;     // EV_TELEMETRY only
    bool has_current;    // EV_TELEMETRY only: false when the window had no valid reading
    analog_stats_t current;
    int msg_id;          // MQTT message id while published and not yet acknowledged, 0 otherwise
} node_event_t;

/**
 * Wall-clock time of an event in Unix milliseconds. Events recorded before NTP set the clock are dated from the
 * uptime, so a stop that happened while the network was still coming up keeps its real time. Returns 0 while the
 * clock is not set (`now_unix_ms` 0): such events wait in the outbox.
 */
int64_t event_time_ms(const node_event_t *event, int64_t now_unix_ms, int64_t now_uptime_ms);

/**
 * Messages waiting for the broker, oldest first. Only the oldest is ever in flight, so they arrive in order, which
 * the platform requires (it drops a message older than the last it applied). When full, the oldest telemetry goes
 * first, and a state change only when nothing else is left: losing a 10 s average is harmless, losing a stop is
 * not, and the platform heals a missing state from the next telemetry anyway.
 */
typedef struct {
    node_event_t *items;
    size_t capacity;
    size_t count;
    uint32_t dropped;
} event_outbox_t;

void event_outbox_init(event_outbox_t *outbox, node_event_t *storage, size_t capacity);

void event_outbox_push(event_outbox_t *outbox, const node_event_t *event);

/** The oldest message, or NULL when empty. */
node_event_t *event_outbox_front(event_outbox_t *outbox);

/** Removes the oldest message if it is the one published as `msg_id`. Returns true if it was removed. */
bool event_outbox_ack(event_outbox_t *outbox, int msg_id);

/** After a disconnection, or a PUBACK that never came: the message in flight is sent again. */
void event_outbox_requeue(event_outbox_t *outbox);

/** Removes the oldest message whatever its state, for one that cannot be encoded and would block the rest. */
void event_outbox_drop_front(event_outbox_t *outbox);

/**
 * Sequence numbers that keep growing across reboots, as the platform needs (it ignores a (gateway, seq) it already
 * saw). The high bits are a block number kept in flash and raised once per boot, the low 20 bits count messages
 * within the block, so the flash is written once per boot and once per million messages.
 */
#define SEQ_BLOCK_BITS 20

typedef struct {
    uint32_t block;
    uint32_t next;
} seq_gen_t;

/** `stored_block` is the block saved by the previous boot (0 if none). The caller saves `gen->block`. */
void seq_init(seq_gen_t *gen, uint32_t stored_block);

/** Next number. Sets `*save_block` when the block changed and must be saved before the number is used. */
uint64_t seq_next(seq_gen_t *gen, bool *save_block);

#ifdef __cplusplus
}
#endif
