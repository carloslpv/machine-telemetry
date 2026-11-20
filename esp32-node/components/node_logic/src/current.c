#include "current.h"

#include <math.h>

void rms_reset(rms_acc_t *acc)
{
    acc->sum = 0;
    acc->sum_sq = 0;
    acc->n = 0;
}

void rms_add(rms_acc_t *acc, int16_t raw)
{
    acc->sum += raw;
    acc->sum_sq += (int64_t)raw * raw;
    acc->n++;
}

double rms_volts(const rms_acc_t *acc, double volts_per_lsb)
{
    if (acc->n == 0) {
        return -1;
    }
    double n = (double)acc->n;
    double mean = (double)acc->sum / n;
    double variance = (double)acc->sum_sq / n - mean * mean;
    // rounding can leave a tiny negative value for a constant signal
    if (variance < 0) {
        variance = 0;
    }
    return sqrt(variance) * volts_per_lsb;
}

double current_amps(double rms_volts, double amps_per_volt, uint32_t turns)
{
    if (rms_volts < 0) {
        return -1;
    }
    return rms_volts * amps_per_volt / (double)(turns == 0 ? 1 : turns);
}
