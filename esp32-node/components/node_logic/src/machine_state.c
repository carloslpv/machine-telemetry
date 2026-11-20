#include "machine_state.h"

#include <stddef.h>

const char *machine_state_name(machine_state_t state)
{
    switch (state) {
    case MS_RUNNING:
        return "RUNNING";
    case MS_IDLE:
        return "IDLE";
    case MS_FAULT:
        return "FAULT";
    case MS_NO_DATA:
        return "NO_DATA";
    default:
        return NULL;
    }
}

void detector_init(detector_t *d, const detector_config_t *cfg)
{
    d->cfg = *cfg;
    d->state = MS_UNKNOWN;
    d->fault_code = 0;
    d->candidate = MS_UNKNOWN;
    d->count = 0;
}

static machine_state_t target_for(const detector_t *d, double amps)
{
    double ma = amps * 1000.0;
    if (d->cfg.overcurrent_ma > 0 && ma >= d->cfg.overcurrent_ma) {
        return MS_FAULT;
    }
    // a machine already turning (or faulted while turning) stays on until the current falls below the lower
    // threshold; a stopped one needs the higher threshold to start
    bool on = d->state == MS_RUNNING || d->state == MS_FAULT;
    double threshold = on ? d->cfg.stop_ma : d->cfg.run_ma;
    return ma >= threshold ? MS_RUNNING : MS_IDLE;
}

static bool set_state(detector_t *d, machine_state_t state)
{
    int code = state == MS_FAULT ? d->cfg.fault_code : 0;
    bool changed = state != d->state || code != d->fault_code;
    d->state = state;
    d->fault_code = code;
    d->candidate = MS_UNKNOWN;
    d->count = 0;
    return changed;
}

bool detector_update(detector_t *d, bool valid, double amps)
{
    if (!valid || amps < 0) {
        return set_state(d, MS_NO_DATA);
    }
    machine_state_t target = target_for(d, amps);
    if (d->state == MS_UNKNOWN || d->state == MS_NO_DATA) {
        return set_state(d, target);
    }
    if (target == d->state) {
        d->candidate = MS_UNKNOWN;
        d->count = 0;
        return false;
    }
    if (target == d->candidate) {
        d->count++;
    } else {
        d->candidate = target;
        d->count = 1;
    }
    uint32_t needed = target == MS_IDLE ? d->cfg.stop_seconds : d->cfg.run_seconds;
    if (d->count >= (needed == 0 ? 1 : needed)) {
        return set_state(d, target);
    }
    return false;
}
