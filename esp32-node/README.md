# esp32-node

Firmware for a machine **without a PLC**: an ESP32 with a clamp-on current sensor (SCT-013) around one wire of the
motor tells whether the machine is running or stopped, and how much current it draws. It publishes the same MQTT
messages as the [gateway](../gateway) ([contract](../docs/mqtt.md)), so the platform, the stops and the Grafana
dashboard work unchanged.

```
SCT-013 ── AC voltage ──> ADS1115 (860 samples/s, I2C) ──> RMS every second ──> RUNNING / IDLE ──> outbox (RAM) ──> MQTT
                                                                           └──> avg/min/max every 10 s ──┘
```

Nothing is installed inside the machine's panel and no wire is cut: the clamp closes around one conductor of the
motor's supply. That is what makes it a fit for an older machine, or one whose PLC nobody may touch.

## Hardware

| Part | Notes |
|---|---|
| ESP32 DevKit (V1) | any ESP32 with Wi-Fi |
| SCT-013-030 | voltage output, 1 V at 30 A, burden resistor built in. Other models: see `MT_CT_AMPS_PER_VOLT` |
| ADS1115 breakout | 16-bit ADC on I2C; reads the small AC signal with far less noise than the ESP32's own ADC |
| 2 × 10 kΩ resistors, 1 × 10 µF capacitor | bias for the sensor's floating output |
| 3.5 mm jack socket (optional) | the SCT-013 comes with a plug; cutting it also works |

### Wiring

| From | To |
|---|---|
| ADS1115 VDD / GND | ESP32 3V3 / GND |
| ADS1115 SCL / SDA | GPIO 22 / GPIO 21 |
| ADS1115 ALERT/RDY | GPIO 19 |
| ADS1115 ADDR | GND (address 0x48) |
| 10 kΩ + 10 kΩ in series | between 3V3 and GND; the middle point goes to ADS1115 **A1**, with the 10 µF capacitor from there to GND |
| SCT-013, one wire | ADS1115 **A0** |
| SCT-013, other wire | ADS1115 **A1** (the middle point) |

The sensor's output swings about 1.4 V above and below zero at full scale. The divider lifts it to the middle of the
ADC's range (1.65 V), and the ADC measures A0 against A1, so the offset cancels out. The firmware also removes any
DC that is left before computing the RMS.

The ALERT/RDY wire makes the ADC signal each finished conversion, so the ESP32 reads every one of the 860 samples per
second exactly once, about 14 per mains cycle, instead of guessing the timing.

**Safety.** The clamp goes around **one** conductor only (a phase, or the neutral). Around a whole cable the
currents cancel and it reads zero. It never touches the copper, but the panel or the extension it is clamped onto
is live: open, clamp and close everything with the power off. Do not use the SCT-013-000 (current output, no burden
resistor) as is: open, it produces dangerous voltages.

**Small loads.** A fan or a small motor draws tenths of an amp, little for a 30 A clamp. Passing the wire three times
through the clamp triples the signal; set `MT_CT_TURNS` to 3 and the firmware divides it back.

## Building and flashing

Requires [ESP-IDF 5.4](https://docs.espressif.com/projects/esp-idf/en/v5.4/esp32/get-started/).

```bash
cd esp32-node
idf.py set-target esp32
idf.py menuconfig       # menu "machine-telemetry node": Wi-Fi, broker, password, ids, pins, thresholds
idf.py build flash monitor
```

Credentials live in `sdkconfig`, which git ignores.

| Setting | Default | What it is |
|---|---|---|
| `MT_MQTT_BROKER_URI` | `mqtt://192.168.0.10:1883` | the machine-telemetry Mosquitto (in the demo case it is on port 1884) |
| `MT_MQTT_PASSWORD` | (empty) | `MQTT_NODE_PASSWORD` of the compose `.env` |
| `MT_SITE`, `MT_GATEWAY_ID`, `MT_MACHINE_ID` | `maleta`, `node-maleta-01`, `MAQ-01` | must match the platform's machine and the broker ACL; the demo data already has these |
| `MT_CT_AMPS_PER_VOLT`, `MT_CT_TURNS` | 30, 1 | clamp model and turns |
| `MT_RUN_THRESHOLD_MA`, `MT_STOP_THRESHOLD_MA` | 100, 60 | running at or above 100 mA, stopped below 60 mA |
| `MT_RUN_SECONDS`, `MT_STOP_SECONDS` | 2, 3 | seconds of confirmation before the state changes |
| `MT_OVERCURRENT_MA` | 0 (off) | at or above it the machine is in FAULT with code 201 ("Sobrecarga do motor") |
| `MT_TELEMETRY_SECONDS` | 10 | telemetry window |

### Choosing the thresholds

With `MT_LOG_CURRENT` on (the default), the serial monitor prints the current every second:

```
I (5310) node: 0.004 A, IDLE, outbox 0
I (6310) node: 0.212 A, IDLE, outbox 0
I (7310) node: 0.215 A, IDLE, outbox 0
I (7311) node: IDLE -> RUNNING
```

Note the current with the machine off and running (and idling, if it has a no-load state), and put the run threshold
a little under the lowest running value and the stop threshold between the two. For a machine that idles with the
motor on, the threshold separates "turning without load" from "working" in the same way.

## Without hardware

The messages are plain MQTT, so the platform side can be tried with `mosquitto_pub` as the node
(`tools/e2e.sh` does exactly that):

```bash
docker compose exec mosquitto mosquitto_pub -u node-maleta-01 -P "$MQTT_NODE_PASSWORD" -q 1 \
  -t mt/maleta/MAQ-01/state \
  -m '{"seq":1,"gatewayId":"node-maleta-01","machineId":"MAQ-01","at":"2026-11-20T12:00:00.000Z","state":"RUNNING","faultCode":0,"previousState":null,"previousFaultCode":null}'
```

## How it behaves

- **Measuring never waits for the network.** Sampling runs on its own core and priority, states and telemetry go to
  an outbox in RAM (360 messages by default, an hour of telemetry), and a separate task publishes. A stop that
  happens while Wi-Fi is down is published with its real time when the connection is back.
- **In order, at least once.** Only the oldest message is in flight; it leaves the outbox after the broker's PUBACK.
  A lost PUBACK means a resend, and the platform drops the copy by `(gatewayId, seq)`.
- **Sequence numbers survive reboots.** The high bits are a block number kept in flash and raised once per boot, so
  numbers never repeat and flash is written once per boot.
- **The clock comes first.** Every message carries the time it happened. Nothing is published before NTP sets the
  clock; what was measured before that is dated back from the uptime.
- **When full, telemetry goes first.** A 10 s average is expendable, a stop is not.
- **No ADC, no guess.** If the ADS1115 stops answering (loose wire, no RDY pulses), the state becomes `NO_DATA`, as
  the gateway does with an unreachable PLC, and the firmware keeps trying to reconfigure the chip.
- **Hysteresis and confirmation.** Two thresholds and a few seconds of confirmation keep the inrush of a starting
  motor, or a current hovering at the limit, from creating stops that never happened.

## Layout

| Path | What it is |
|---|---|
| [`components/node_logic`](components/node_logic) | hardware-free logic (RMS, state detection, windows, outbox, sequence, JSON), tested on the PC with ASan and UBSan |
| [`main/ads1115.c`](main/ads1115.c) | ADS1115 driver: continuous differential conversion and the conversion-ready interrupt |
| [`main/mqtt_link.c`](main/mqtt_link.c) | publishing the outbox, status and last will |
| [`main/main.c`](main/main.c) | sampling and node tasks, NTP, startup |

```bash
cmake -S components/node_logic/test -B build/node-test && cmake --build build/node-test && ./build/node-test/node_test
```

## Limitations

- Current only: no piece counter (a digital input with a sensor on the output conveyor is the natural next step),
  so the OEE of this machine has availability but no performance or quality.
- The outbox is in RAM: a power cut loses what was not sent yet.
- One clamp per node; a three-phase motor is measured on one phase, which is enough to know whether it turns.
- No TLS in the demo configuration (`mqtt://`); use `mqtts://` and a broker certificate over the internet.
