#pragma once

#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

/**
 * Accumulates raw ADC samples of the current transformer's output over one measuring window (one second).
 * Integers only, so adding a sample is cheap enough for the sampling task: 860 samples of an int16 keep the sum of
 * squares far below the int64 limit.
 */
typedef struct {
    int64_t sum;
    int64_t sum_sq;
    uint32_t n;
} rms_acc_t;

void rms_reset(rms_acc_t *acc);

void rms_add(rms_acc_t *acc, int16_t raw);

/**
 * RMS of the AC part of the signal, in volts: the mean (the DC offset of the bias divider, or of the ADC itself) is
 * removed first, so only the alternating current counts. Returns -1 when no sample was added.
 */
double rms_volts(const rms_acc_t *acc, double volts_per_lsb);

/**
 * Current through the clamp. `amps_per_volt` is the transformer's rating (30 for an SCT-013-030, which gives 1 V at
 * 30 A); `turns` is how many times the wire passes through the clamp, which multiplies the signal of a small load.
 */
double current_amps(double rms_volts, double amps_per_volt, uint32_t turns);

#ifdef __cplusplus
}
#endif
