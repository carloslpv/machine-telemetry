# MQTT contract (gateway → platform)

All messages are JSON, QoS 1. `<prefix>` is `mt` by default, `<site>` and `<machine>` are the ids in the gateway
config. Times are UTC, ISO 8601, taken from the gateway's clock (keep it on NTP).

## `<prefix>/<site>/<machine>/state`

Sent as soon as the gateway sees the state change, or the fault code change while faulted.

```json
{"seq": 1842, "gatewayId": "gw-joacaba-01", "machineId": "ENS-01", "at": "2026-10-31T10:12:03.250Z",
 "state": "FAULT", "faultCode": 102, "previousState": "RUNNING", "previousFaultCode": 0}
```

`state` is `RUNNING`, `IDLE`, `FAULT` or `NO_DATA` (the gateway could not read the PLC).

## `<prefix>/<site>/<machine>/telemetry`

Once per telemetry window (10 s by default).

```json
{"seq": 1843, "gatewayId": "gw-joacaba-01", "machineId": "ENS-01", "at": "2026-10-31T10:12:10.000Z",
 "state": "FAULT", "faultCode": 102, "windowSeconds": 10.0,
 "goodCount": 1234567, "rejectCount": 2210, "counterBits": 32,
 "analogs": {"motor-temp": {"avg": 71.3, "min": 71.1, "max": 71.6, "last": 71.4, "samples": 40}}}
```

Counters are the raw PLC values, unsigned; the platform takes differences modulo `2^counterBits`.

## `<prefix>/<site>/gateway/<gatewayId>/status`

Retained. `online` when the gateway connects, `offline` when it stops, and `offline` again from the broker (last
will) if the gateway disappears.

```json
{"gatewayId": "gw-joacaba-01", "status": "online", "at": "2026-10-31T10:00:00Z"}
```

## Delivery

`seq` comes from the gateway's outbox: increasing, never reused, kept across restarts. The gateway deletes a message
from the outbox only after the broker's PUBACK, so a message can arrive twice; the platform stores
`(gatewayId, seq)` and ignores the copy. Messages of a machine arrive in order, and the platform drops one older
than the last it applied.
