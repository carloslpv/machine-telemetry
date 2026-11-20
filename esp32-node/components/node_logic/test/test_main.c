#include <math.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "backoff.h"
#include "current.h"
#include "machine_state.h"
#include "message.h"
#include "node_event.h"

static int failures;

#define CHECK(cond)                                                                  \
    do {                                                                             \
        if (!(cond)) {                                                               \
            fprintf(stderr, "%s:%d: CHECK(%s) failed\n", __FILE__, __LINE__, #cond); \
            failures++;                                                              \
        }                                                                            \
    } while (0)

#define NEAR(a, b, tol) (fabs((a) - (b)) <= (tol))

// ADS1115 at +-2.048 V: 62.5 uV per step
#define LSB (2.048 / 32768.0)

/**
 * One second of an SCT-013-030 (1 V at 30 A) at 860 samples per second on 60 Hz mains, over a DC offset, as the
 * sampling task sees it.
 */
static rms_acc_t sine_second(double amps, double offset_volts)
{
    rms_acc_t acc;
    rms_reset(&acc);
    const double pi = 3.14159265358979323846;
    for (int i = 0; i < 860; i++) {
        double volts = offset_volts + amps / 30.0 * sqrt(2.0) * sin(2 * pi * 60.0 * i / 860.0 + 0.3);
        double raw = round(volts / LSB);
        rms_add(&acc, (int16_t)(raw > 32767 ? 32767 : raw < -32768 ? -32768 : raw));
    }
    return acc;
}

static void test_rms_measures_the_current_and_ignores_the_offset(void)
{
    rms_acc_t acc = sine_second(10.0, 0.0);
    CHECK(NEAR(current_amps(rms_volts(&acc, LSB), 30, 1), 10.0, 0.05));

    // a DC offset (differential input not perfectly balanced) must not read as current
    acc = sine_second(10.0, 0.05);
    CHECK(NEAR(current_amps(rms_volts(&acc, LSB), 30, 1), 10.0, 0.05));
    acc = sine_second(0.0, 0.05);
    CHECK(NEAR(current_amps(rms_volts(&acc, LSB), 30, 1), 0.0, 0.002));

    // a small fan, with the wire through the clamp three times
    acc = sine_second(0.2 * 3, 0.0);
    CHECK(NEAR(current_amps(rms_volts(&acc, LSB), 30, 3), 0.2, 0.01));

    rms_reset(&acc);
    CHECK(rms_volts(&acc, LSB) < 0);
    CHECK(current_amps(-1, 30, 1) < 0);
}

static detector_config_t config(void)
{
    detector_config_t cfg = {
        .run_ma = 100, .stop_ma = 60, .overcurrent_ma = 0, .run_seconds = 2, .stop_seconds = 3, .fault_code = 201,
    };
    return cfg;
}

static void test_detector_first_value_sets_the_state(void)
{
    detector_config_t cfg = config();
    detector_t d;
    detector_init(&d, &cfg);
    CHECK(d.state == MS_UNKNOWN);
    CHECK(detector_update(&d, true, 0.5));
    CHECK(d.state == MS_RUNNING);

    detector_init(&d, &cfg);
    CHECK(detector_update(&d, true, 0.01));
    CHECK(d.state == MS_IDLE && d.fault_code == 0);
}

static void test_detector_debounces_and_has_hysteresis(void)
{
    detector_config_t cfg = config();
    detector_t d;
    detector_init(&d, &cfg);
    detector_update(&d, true, 0.0);

    // one spike is not a start
    CHECK(!detector_update(&d, true, 0.5));
    CHECK(!detector_update(&d, true, 0.0));
    CHECK(d.state == MS_IDLE);

    // two seconds above the run threshold are
    CHECK(!detector_update(&d, true, 0.5));
    CHECK(detector_update(&d, true, 0.5));
    CHECK(d.state == MS_RUNNING);

    // between the thresholds a running machine stays running
    for (int i = 0; i < 10; i++) {
        CHECK(!detector_update(&d, true, 0.08));
    }
    CHECK(d.state == MS_RUNNING);

    // and a stopped one stays stopped
    for (int i = 0; i < 3; i++) {
        detector_update(&d, true, 0.0);
    }
    CHECK(d.state == MS_IDLE);
    for (int i = 0; i < 10; i++) {
        CHECK(!detector_update(&d, true, 0.08));
    }
    CHECK(d.state == MS_IDLE);
}

static void test_detector_stop_needs_three_seconds(void)
{
    detector_config_t cfg = config();
    detector_t d;
    detector_init(&d, &cfg);
    detector_update(&d, true, 1.0);
    CHECK(!detector_update(&d, true, 0.0));
    CHECK(!detector_update(&d, true, 0.0));
    CHECK(detector_update(&d, true, 0.0));
    CHECK(d.state == MS_IDLE);
}

static void test_detector_overcurrent_and_no_data(void)
{
    detector_config_t cfg = config();
    cfg.overcurrent_ma = 5000;
    detector_t d;
    detector_init(&d, &cfg);
    detector_update(&d, true, 1.0);
    CHECK(!detector_update(&d, true, 6.0));
    CHECK(detector_update(&d, true, 6.0));
    CHECK(d.state == MS_FAULT && d.fault_code == 201);

    // back to normal current: running again
    detector_update(&d, true, 1.0);
    CHECK(detector_update(&d, true, 1.0));
    CHECK(d.state == MS_RUNNING && d.fault_code == 0);

    // an unreadable ADC is NO_DATA at once, and the next valid value sets the state at once
    CHECK(detector_update(&d, false, 0));
    CHECK(d.state == MS_NO_DATA);
    CHECK(!detector_update(&d, false, 0));
    CHECK(detector_update(&d, true, 0.0));
    CHECK(d.state == MS_IDLE);
}

static node_event_t event(event_kind_t kind, uint64_t seq)
{
    node_event_t e = { .kind = kind, .seq = seq, .uptime_ms = 1000, .unix_ms = 0, .state = MS_RUNNING };
    return e;
}

static void test_outbox_sends_in_order_and_requeues(void)
{
    node_event_t storage[4];
    event_outbox_t outbox;
    event_outbox_init(&outbox, storage, 4);
    CHECK(event_outbox_front(&outbox) == NULL);

    node_event_t e1 = event(EV_STATE, 1), e2 = event(EV_TELEMETRY, 2);
    event_outbox_push(&outbox, &e1);
    event_outbox_push(&outbox, &e2);
    node_event_t *front = event_outbox_front(&outbox);
    CHECK(front->seq == 1);
    front->msg_id = 7;

    CHECK(!event_outbox_ack(&outbox, 8));  // someone else's PUBACK
    CHECK(event_outbox_front(&outbox)->seq == 1);

    event_outbox_requeue(&outbox);  // connection lost before the PUBACK
    CHECK(event_outbox_front(&outbox)->msg_id == 0);
    event_outbox_front(&outbox)->msg_id = 9;
    CHECK(!event_outbox_ack(&outbox, 7));  // late PUBACK of the first try
    CHECK(event_outbox_ack(&outbox, 9));
    CHECK(event_outbox_front(&outbox)->seq == 2);
    CHECK(event_outbox_ack(&outbox, 0) == false);

    event_outbox_drop_front(&outbox);
    CHECK(event_outbox_front(&outbox) == NULL && outbox.dropped == 1);
    event_outbox_drop_front(&outbox);  // empty: nothing happens
    CHECK(outbox.dropped == 1);
}

static void test_outbox_drops_telemetry_before_state_changes(void)
{
    node_event_t storage[3];
    event_outbox_t outbox;
    event_outbox_init(&outbox, storage, 3);
    node_event_t s1 = event(EV_STATE, 1), t2 = event(EV_TELEMETRY, 2), s3 = event(EV_STATE, 3);
    node_event_t s4 = event(EV_STATE, 4), s5 = event(EV_STATE, 5);
    event_outbox_push(&outbox, &s1);
    event_outbox_push(&outbox, &t2);
    event_outbox_push(&outbox, &s3);
    event_outbox_front(&outbox)->msg_id = 5;  // s1 in flight

    event_outbox_push(&outbox, &s4);  // full: t2 goes
    CHECK(outbox.count == 3 && outbox.dropped == 1);
    CHECK(storage[0].seq == 1 && storage[1].seq == 3 && storage[2].seq == 4);

    event_outbox_push(&outbox, &s5);  // only state changes left: the oldest not in flight goes, never s1
    CHECK(outbox.dropped == 2);
    CHECK(storage[0].seq == 1 && storage[0].msg_id == 5 && storage[1].seq == 4 && storage[2].seq == 5);
    CHECK(event_outbox_ack(&outbox, 5));
}

static void test_event_time(void)
{
    node_event_t e = event(EV_STATE, 1);
    e.uptime_ms = 4000;
    CHECK(event_time_ms(&e, 0, 9000) == 0);  // clock not set: wait
    // set later: dated back from the uptime
    CHECK(event_time_ms(&e, 1761905523000LL, 9000) == 1761905518000LL);
    e.unix_ms = 1761905000000LL;
    CHECK(event_time_ms(&e, 1761905523000LL, 9000) == 1761905000000LL);
}

static void test_seq_grows_across_reboots(void)
{
    seq_gen_t gen;
    bool save;
    seq_init(&gen, 0);
    uint64_t first = seq_next(&gen, &save);
    CHECK(!save && first == (1ULL << SEQ_BLOCK_BITS));
    CHECK(seq_next(&gen, &save) == first + 1);

    seq_gen_t after_reboot;
    seq_init(&after_reboot, gen.block);
    CHECK(seq_next(&after_reboot, &save) > first + 1);

    gen.next = 1u << SEQ_BLOCK_BITS;  // block used up
    uint64_t next = seq_next(&gen, &save);
    CHECK(save && gen.block == 2 && next == (2ULL << SEQ_BLOCK_BITS));
}

static const node_ids_t IDS = {
    .prefix = "mt", .site = "maleta", .gateway_id = "node-maleta-01", .machine_id = "MAQ-01", .analog = "motor-current",
};

static void test_messages(void)
{
    char buf[512];
    CHECK(node_ids_valid(&IDS));
    node_ids_t bad = IDS;
    bad.machine_id = "MAQ 01";
    CHECK(!node_ids_valid(&bad));
    bad.machine_id = "";
    CHECK(!node_ids_valid(&bad));
    bad.machine_id = "a/b";
    CHECK(!node_ids_valid(&bad));

    CHECK(message_format_time(buf, sizeof(buf), 1761905523250LL) > 0);
    CHECK(strcmp(buf, "2025-10-31T10:12:03.250Z") == 0);

    message_topic(buf, sizeof(buf), &IDS, EV_STATE);
    CHECK(strcmp(buf, "mt/maleta/MAQ-01/state") == 0);
    message_topic(buf, sizeof(buf), &IDS, EV_TELEMETRY);
    CHECK(strcmp(buf, "mt/maleta/MAQ-01/telemetry") == 0);
    message_status_topic(buf, sizeof(buf), &IDS);
    CHECK(strcmp(buf, "mt/maleta/gateway/node-maleta-01/status") == 0);
    CHECK(message_topic(buf, 10, &IDS, EV_STATE) < 0);

    node_event_t s = event(EV_STATE, 1048577);
    s.state = MS_IDLE;
    s.previous_state = MS_RUNNING;
    CHECK(message_encode(buf, sizeof(buf), &IDS, &s, 1761905523250LL) > 0);
    CHECK(strcmp(buf, "{\"seq\":1048577,\"gatewayId\":\"node-maleta-01\",\"machineId\":\"MAQ-01\","
                      "\"at\":\"2025-10-31T10:12:03.250Z\",\"state\":\"IDLE\",\"faultCode\":0,"
                      "\"previousState\":\"RUNNING\",\"previousFaultCode\":0}") == 0);

    s.previous_state = MS_UNKNOWN;  // first state after boot
    message_encode(buf, sizeof(buf), &IDS, &s, 1761905523250LL);
    CHECK(strstr(buf, "\"previousState\":null,\"previousFaultCode\":null}") != NULL);

    node_event_t t = event(EV_TELEMETRY, 1048578);
    t.window_s = 10;
    t.has_current = true;
    t.current = (analog_stats_t){ .avg = 0.2154, .min = 0.2, .max = 0.23, .last = 0.21, .samples = 10 };
    CHECK(message_encode(buf, sizeof(buf), &IDS, &t, 1761905530000LL) > 0);
    CHECK(strcmp(buf, "{\"seq\":1048578,\"gatewayId\":\"node-maleta-01\",\"machineId\":\"MAQ-01\","
                      "\"at\":\"2025-10-31T10:12:10.000Z\",\"state\":\"RUNNING\",\"faultCode\":0,"
                      "\"windowSeconds\":10.0,\"goodCount\":null,\"rejectCount\":null,\"counterBits\":32,"
                      "\"analogs\":{\"motor-current\":{\"avg\":0.215,\"min\":0.200,\"max\":0.230,\"last\":0.210,"
                      "\"samples\":10}}}") == 0);

    t.has_current = false;
    t.state = MS_NO_DATA;
    message_encode(buf, sizeof(buf), &IDS, &t, 1761905530000LL);
    CHECK(strstr(buf, "\"state\":\"NO_DATA\"") != NULL && strstr(buf, "\"analogs\":{}}") != NULL);

    CHECK(message_encode(buf, 40, &IDS, &t, 1761905530000LL) < 0);
    t.state = MS_UNKNOWN;
    CHECK(message_encode(buf, sizeof(buf), &IDS, &t, 1761905530000LL) < 0);

    CHECK(message_encode_status(buf, sizeof(buf), &IDS, "online", 1761905523250LL) > 0);
    CHECK(strcmp(buf, "{\"gatewayId\":\"node-maleta-01\",\"status\":\"online\",\"at\":\"2025-10-31T10:12:03.250Z\"}")
          == 0);
}

static void test_window(void)
{
    analog_window_t w;
    analog_stats_t stats;
    window_reset(&w);
    CHECK(!window_stats(&w, &stats));
    window_add(&w, 0.3);
    window_add(&w, 0.1);
    window_add(&w, 0.2);
    CHECK(window_stats(&w, &stats));
    CHECK(NEAR(stats.avg, 0.2, 1e-9) && stats.min == 0.1 && stats.max == 0.3 && stats.last == 0.2);
    CHECK(stats.samples == 3);
}

static void test_backoff(void)
{
    CHECK(backoff_delay_ms(0, 1000, 60000, 0) == 500);
    CHECK(backoff_delay_ms(20, 1000, 60000, 0xFFFFFFFF) <= 60000);
}

int main(void)
{
    test_rms_measures_the_current_and_ignores_the_offset();
    test_detector_first_value_sets_the_state();
    test_detector_debounces_and_has_hysteresis();
    test_detector_stop_needs_three_seconds();
    test_detector_overcurrent_and_no_data();
    test_outbox_sends_in_order_and_requeues();
    test_outbox_drops_telemetry_before_state_changes();
    test_event_time();
    test_seq_grows_across_reboots();
    test_messages();
    test_window();
    test_backoff();
    if (failures > 0) {
        fprintf(stderr, "%d check(s) failed\n", failures);
        return EXIT_FAILURE;
    }
    printf("all node tests passed\n");
    return EXIT_SUCCESS;
}
