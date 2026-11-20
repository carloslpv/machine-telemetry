#pragma once

#include <stdbool.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

/** The states of docs/mqtt.md, plus UNKNOWN before the first measurement. */
typedef enum {
    MS_UNKNOWN = 0,
    MS_RUNNING,
    MS_IDLE,
    MS_FAULT,
    MS_NO_DATA,
} machine_state_t;

/** Name used in the MQTT messages, or NULL for MS_UNKNOWN. */
const char *machine_state_name(machine_state_t state);

typedef struct {
    uint32_t run_ma;          // at or above this current a stopped machine counts as running
    uint32_t stop_ma;         // below this current a running machine counts as stopped (lower than run_ma)
    uint32_t overcurrent_ma;  // at or above this current the machine counts as faulted; 0 turns it off
    uint32_t run_seconds;     // consecutive seconds needed to enter RUNNING or FAULT
    uint32_t stop_seconds;    // consecutive seconds needed to enter IDLE
    int fault_code;           // code sent with FAULT (201, "Sobrecarga do motor", in the demo fault codes)
} detector_config_t;

/**
 * Turns one current value per second into a machine state. Two thresholds (hysteresis) and a few seconds of
 * confirmation keep a motor that starts with a spike, or a reading that hovers at the limit, from producing a burst
 * of stops that never happened.
 */
typedef struct {
    detector_config_t cfg;
    machine_state_t state;
    int fault_code;
    machine_state_t candidate;
    uint32_t count;
} detector_t;

void detector_init(detector_t *d, const detector_config_t *cfg);

/**
 * Feeds the current of the last second. `valid` is false when the ADC could not be read; the state then becomes
 * NO_DATA at once, as the gateway does when it cannot read a PLC. The first valid value after start or after
 * NO_DATA sets the state directly. Returns true when the state or the fault code changed.
 */
bool detector_update(detector_t *d, bool valid, double amps);

#ifdef __cplusplus
}
#endif
