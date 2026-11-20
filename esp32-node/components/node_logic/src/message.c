#include "message.h"

#include <inttypes.h>
#include <stdio.h>
#include <string.h>
#include <time.h>

static bool name_valid(const char *name)
{
    if (name == NULL) {
        return false;
    }
    size_t len = strlen(name);
    if (len == 0 || len > 32) {
        return false;
    }
    for (size_t i = 0; i < len; i++) {
        char c = name[i];
        bool ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '-' || c == '_'
                  || c == '.';
        if (!ok) {
            return false;
        }
    }
    return true;
}

bool node_ids_valid(const node_ids_t *ids)
{
    return name_valid(ids->prefix) && name_valid(ids->site) && name_valid(ids->gateway_id)
           && name_valid(ids->machine_id) && name_valid(ids->analog);
}

static int fits(int len, size_t size)
{
    return len < 0 || (size_t)len >= size ? -1 : len;
}

int message_format_time(char *buf, size_t size, int64_t unix_ms)
{
    time_t seconds = (time_t)(unix_ms / 1000);
    int millis = (int)(unix_ms % 1000);
    struct tm tm;
    if (unix_ms < 0 || gmtime_r(&seconds, &tm) == NULL) {
        return -1;
    }
    int len = snprintf(buf, size, "%04d-%02d-%02dT%02d:%02d:%02d.%03dZ", tm.tm_year + 1900, tm.tm_mon + 1, tm.tm_mday,
                       tm.tm_hour, tm.tm_min, tm.tm_sec, millis);
    return fits(len, size);
}

int message_topic(char *buf, size_t size, const node_ids_t *ids, event_kind_t kind)
{
    int len = snprintf(buf, size, "%s/%s/%s/%s", ids->prefix, ids->site, ids->machine_id,
                       kind == EV_STATE ? "state" : "telemetry");
    return fits(len, size);
}

int message_status_topic(char *buf, size_t size, const node_ids_t *ids)
{
    int len = snprintf(buf, size, "%s/%s/gateway/%s/status", ids->prefix, ids->site, ids->gateway_id);
    return fits(len, size);
}

/** A state name in quotes, or null. */
static const char *quoted_state(machine_state_t state, char *buf, size_t size)
{
    const char *name = machine_state_name(state);
    if (name == NULL) {
        return "null";
    }
    snprintf(buf, size, "\"%s\"", name);
    return buf;
}

int message_encode(char *buf, size_t size, const node_ids_t *ids, const node_event_t *event, int64_t at_ms)
{
    char at[32];
    const char *state = machine_state_name(event->state);
    if (state == NULL || message_format_time(at, sizeof(at), at_ms) < 0) {
        return -1;
    }
    int len;
    if (event->kind == EV_STATE) {
        char previous[16];
        bool has_previous = event->previous_state != MS_UNKNOWN;
        char previous_code[16] = "null";
        if (has_previous) {
            snprintf(previous_code, sizeof(previous_code), "%d", event->previous_fault_code);
        }
        len = snprintf(buf, size,
                       "{\"seq\":%" PRIu64 ",\"gatewayId\":\"%s\",\"machineId\":\"%s\",\"at\":\"%s\","
                       "\"state\":\"%s\",\"faultCode\":%d,\"previousState\":%s,\"previousFaultCode\":%s}",
                       event->seq, ids->gateway_id, ids->machine_id, at, state, event->fault_code,
                       quoted_state(event->previous_state, previous, sizeof(previous)), previous_code);
    } else {
        char analogs[160] = "{}";
        if (event->has_current) {
            int n = snprintf(analogs, sizeof(analogs),
                             "{\"%s\":{\"avg\":%.3f,\"min\":%.3f,\"max\":%.3f,\"last\":%.3f,\"samples\":%" PRIu32 "}}",
                             ids->analog, event->current.avg, event->current.min, event->current.max,
                             event->current.last, event->current.samples);
            if (fits(n, sizeof(analogs)) < 0) {
                return -1;
            }
        }
        len = snprintf(buf, size,
                       "{\"seq\":%" PRIu64 ",\"gatewayId\":\"%s\",\"machineId\":\"%s\",\"at\":\"%s\","
                       "\"state\":\"%s\",\"faultCode\":%d,\"windowSeconds\":%.1f,"
                       "\"goodCount\":null,\"rejectCount\":null,\"counterBits\":32,\"analogs\":%s}",
                       event->seq, ids->gateway_id, ids->machine_id, at, state, event->fault_code, event->window_s,
                       analogs);
    }
    return fits(len, size);
}

int message_encode_status(char *buf, size_t size, const node_ids_t *ids, const char *status, int64_t at_ms)
{
    char at[32];
    if (message_format_time(at, sizeof(at), at_ms) < 0) {
        return -1;
    }
    int len = snprintf(buf, size, "{\"gatewayId\":\"%s\",\"status\":\"%s\",\"at\":\"%s\"}", ids->gateway_id, status,
                       at);
    return fits(len, size);
}
