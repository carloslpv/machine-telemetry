# platform

Receives what the gateways send and turns it into the numbers a production manager asks for: what each machine is
doing now, how long it stopped and why, and OEE.

Spring Boot 4.1 on Java 25, PostgreSQL 18 with Flyway, plain JDBC. It is the "own cloud platform" the TCC lists as
future work, in place of TagoIO.

## Inputs

| Input | How | Deduplicated by |
|---|---|---|
| Gateway over MQTT | persistent session, manual ack after commit; topics in [docs/mqtt.md](../docs/mqtt.md) | gateway id + outbox sequence |
| LoRaWAN over TTN | webhook `POST /api/v1/integrations/ttn/uplink`, header `X-Webhook-Token`, application id checked | session key + frame counter |

## Model

- `state_interval`: one row per period in a state (RUNNING, IDLE, FAULT with its code, NO_DATA). The open row is the
  current state.
- `production`: units per telemetry window, from the PLC counters (modulo 2^32 over MQTT, 2^16 over LoRaWAN, with
  reset detection).
- `analog_sample`: average, minimum and maximum of each analog value per window.
- `lorawan_uplink`: RSSI, SNR and gateway of each uplink.

OEE, time per state and stops are SQL functions (`machine_oee`, `machine_state_seconds`, `machine_stops` in
[V2__oee.sql](src/main/resources/db/migration/V2__oee.sql)), so the API and the Grafana dashboard cannot disagree.

## API

All under `/api/v1`, with `X-API-Key`. `from` and `to` are ISO instants; without them the window is today in
`platform.zone`.

| Method | Path | |
|---|---|---|
| GET | `/machines` | machines, current state and since when, stale flag |
| GET | `/machines/{id}/oee` | availability, performance, quality, OEE, seconds per state, good and rejected units |
| GET | `/machines/{id}/stops` | each stop with duration and cause |
| GET | `/machines/{id}/stops/pareto` | stops grouped by cause, longest total first |
| PUT | `/machines/{id}` | register or update a machine (site, name, ideal cycle, optional LoRaWAN device and port) |
| PUT | `/machines/{id}/fault-codes/{code}` | description of a fault code |

## Build and test

```bash
./mvnw verify   # needs Docker for PostgreSQL (Testcontainers)
```

The integration tests run a scripted hour through the ingestion and check every OEE factor against hand-computed
values, plus redelivery, unknown machines, wrong site, gaps that become NO_DATA, late messages, a dropped state
change repaired by telemetry, the TTN webhook (token, application, unknown device, retried uplink, lost uplinks
across a 16-bit counter wrap) and the MQTT path through an embedded broker.
