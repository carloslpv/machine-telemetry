# gateway

Edge gateway. Runs on whatever Linux box sits next to the machine (a Raspberry Pi 4, an ARM TV box with Debian, an
industrial PC), reads the PLC and sends machine states and telemetry to the platform.

```
PLC ── OPC UA or S7 PUT/GET ──> read every 250 ms ──> state changes + 10 s aggregates ──> outbox (SQLite) ──> MQTT
                                                                                    └──> LoRaWAN modem (AT commands)
```

Plain Java 25, no framework: one jar of about 15 MB, a few virtual threads, and around 60 MB of heap, so it fits
the 1 GB boxes this is meant for.

## Configuration

One YAML file; [gateway.example.yaml](gateway.example.yaml) has both kinds of source and both uplinks commented.
`${NAME:default}` takes the value from an environment variable.

| Section | What it sets |
|---|---|
| `machines[].source` | `opcua` (endpoint, namespace URI, optional user) or `s7` (host, rack, slot) |
| `machines[].signals` | where `running`, `fault`, `fault-code`, `good-count`, `reject-count` are in the PLC |
| `machines[].analogs` | any number of analog values, with an optional linear `scale` (the SCALE_X of the PLC, done here) |
| `uplink` | `mqtt` (broker, outbox size) or `lorawan` (serial device, AT command template, intervals) |

Before going live, check the addresses:

```bash
java -jar target/gateway.jar --check gateway.yaml
```

It reads every machine once and prints each value, which is how you find out that a DB is optimized or PUT/GET is
not allowed before anything is sent.

## Build and test

```bash
./mvnw verify
docker build -t machine-telemetry-gateway .
```

The tests cover the S7 frames (against the bytes Snap7 sends), a fake CPU that forces a read to be split, state
detection and aggregation, the SQLite outbox (order, restart, limits), MQTT store and forward against an in-process
broker that goes away and comes back, the AT command dialogue (OK, busy, error, two RxConfig lines), and the
LoRaWAN payload against [the shared test vectors](../lorawan/test-vectors.json). Reading a real OPC UA server and a
real S7 server is covered by the end-to-end test, against [plc-sim](../plc-sim).
