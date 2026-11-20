#pragma once

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

#include "node_event.h"

#ifdef __cplusplus
extern "C" {
#endif

/** Names that go into the topics and the JSON, from the firmware configuration. */
typedef struct {
    const char *prefix;      // "mt"
    const char *site;        // must match the machine's site on the platform and the gateway's ACL entry
    const char *gateway_id;  // also the MQTT user name
    const char *machine_id;  // must exist on the platform
    const char *analog;      // name of the current in the analogs ("motor-current", as the Grafana panel expects)
} node_ids_t;

/**
 * True when every name is 1 to 32 characters of letters, digits, '-', '_' or '.', so it can go into a topic and a
 * JSON string as is.
 */
bool node_ids_valid(const node_ids_t *ids);

/** ISO 8601 in UTC with milliseconds, as the gateway writes it: 2026-10-31T10:12:03.250Z. */
int message_format_time(char *buf, size_t size, int64_t unix_ms);

/** Topic of an event: <prefix>/<site>/<machine>/state or .../telemetry. Negative if it does not fit. */
int message_topic(char *buf, size_t size, const node_ids_t *ids, event_kind_t kind);

/** <prefix>/<site>/gateway/<gateway id>/status */
int message_status_topic(char *buf, size_t size, const node_ids_t *ids);

/**
 * JSON of docs/mqtt.md for an event dated `at_ms`. There are no piece counters on this node, so goodCount and
 * rejectCount are null and only the current goes in the analogs. Returns the length, or -1 if it does not fit.
 */
int message_encode(char *buf, size_t size, const node_ids_t *ids, const node_event_t *event, int64_t at_ms);

/** {"gatewayId":...,"status":"online","at":...} */
int message_encode_status(char *buf, size_t size, const node_ids_t *ids, const char *status, int64_t at_ms);

#ifdef __cplusplus
}
#endif
