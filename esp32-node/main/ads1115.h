#pragma once

#include <stdint.h>

#include "esp_err.h"

typedef enum {
    ADS1115_RANGE_2048 = 2,  // values are the PGA field of the config register
    ADS1115_RANGE_1024 = 3,
    ADS1115_RANGE_512 = 4,
} ads1115_range_t;

typedef struct {
    int sda_gpio;
    int scl_gpio;
    int rdy_gpio;
    uint8_t address;
    ads1115_range_t range;
} ads1115_config_t;

/**
 * Sets up I2C and the conversion-ready interrupt, then calls ads1115_configure. Call it from the task that will call
 * ads1115_read_next: the interrupt wakes that task.
 */
esp_err_t ads1115_start(const ads1115_config_t *config);

/**
 * Writes the ADC's registers: continuous conversion of AIN0 - AIN1 (differential) at 860 samples per second, and
 * the ALERT/RDY pin pulsing after every conversion. Safe to call again after a power glitch reset the chip.
 */
esp_err_t ads1115_configure(void);

/** Volts per step of the raw value at the configured range. */
double ads1115_volts_per_lsb(void);

/** Waits for the next conversion (at most `timeout_ms`) and reads it. */
esp_err_t ads1115_read_next(int16_t *raw, uint32_t timeout_ms);
