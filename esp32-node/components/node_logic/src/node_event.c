#include "node_event.h"

#include <string.h>

void window_reset(analog_window_t *w)
{
    memset(w, 0, sizeof(*w));
}

void window_add(analog_window_t *w, double value)
{
    if (w->n == 0 || value < w->min) {
        w->min = value;
    }
    if (w->n == 0 || value > w->max) {
        w->max = value;
    }
    w->sum += value;
    w->last = value;
    w->n++;
}

bool window_stats(const analog_window_t *w, analog_stats_t *out)
{
    if (w->n == 0) {
        return false;
    }
    out->avg = w->sum / w->n;
    out->min = w->min;
    out->max = w->max;
    out->last = w->last;
    out->samples = w->n;
    return true;
}

int64_t event_time_ms(const node_event_t *event, int64_t now_unix_ms, int64_t now_uptime_ms)
{
    if (event->unix_ms != 0) {
        return event->unix_ms;
    }
    if (now_unix_ms == 0) {
        return 0;
    }
    return now_unix_ms - (now_uptime_ms - event->uptime_ms);
}

void event_outbox_init(event_outbox_t *outbox, node_event_t *storage, size_t capacity)
{
    outbox->items = storage;
    outbox->capacity = capacity;
    outbox->count = 0;
    outbox->dropped = 0;
}

static void remove_at(event_outbox_t *outbox, size_t index)
{
    memmove(&outbox->items[index], &outbox->items[index + 1], (outbox->count - index - 1) * sizeof(node_event_t));
    outbox->count--;
}

/** Index of the message to drop when full: never the one in flight, telemetry before state changes. */
static size_t victim(const event_outbox_t *outbox)
{
    size_t first = outbox->items[0].msg_id != 0 ? 1 : 0;
    for (size_t i = first; i < outbox->count; i++) {
        if (outbox->items[i].kind == EV_TELEMETRY) {
            return i;
        }
    }
    return first;
}

void event_outbox_push(event_outbox_t *outbox, const node_event_t *event)
{
    if (outbox->capacity == 0) {
        outbox->dropped++;
        return;
    }
    if (outbox->count == outbox->capacity) {
        size_t index = victim(outbox);
        if (index >= outbox->count) {
            // capacity 1 with the only message in flight: the new one is what gets lost
            outbox->dropped++;
            return;
        }
        remove_at(outbox, index);
        outbox->dropped++;
    }
    outbox->items[outbox->count] = *event;
    outbox->items[outbox->count].msg_id = 0;
    outbox->count++;
}

node_event_t *event_outbox_front(event_outbox_t *outbox)
{
    return outbox->count == 0 ? NULL : &outbox->items[0];
}

bool event_outbox_ack(event_outbox_t *outbox, int msg_id)
{
    if (outbox->count == 0 || msg_id == 0 || outbox->items[0].msg_id != msg_id) {
        return false;
    }
    remove_at(outbox, 0);
    return true;
}

void event_outbox_requeue(event_outbox_t *outbox)
{
    if (outbox->count > 0) {
        outbox->items[0].msg_id = 0;
    }
}

void event_outbox_drop_front(event_outbox_t *outbox)
{
    if (outbox->count > 0) {
        remove_at(outbox, 0);
        outbox->dropped++;
    }
}

void seq_init(seq_gen_t *gen, uint32_t stored_block)
{
    gen->block = stored_block + 1;
    gen->next = 0;
}

uint64_t seq_next(seq_gen_t *gen, bool *save_block)
{
    *save_block = false;
    if (gen->next >= (1u << SEQ_BLOCK_BITS)) {
        gen->block++;
        gen->next = 0;
        *save_block = true;
    }
    return ((uint64_t)gen->block << SEQ_BLOCK_BITS) | gen->next++;
}
