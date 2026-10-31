# plc-sim

Simulated Siemens S7-1200 running a feed bagging machine. It serves the same data block two ways, as the real CPU
does:

| Interface | Address | What it reads |
|---|---|---|
| OPC UA | `opc.tcp://<host>:4840`, namespace `http://www.siemens.com/simatic-s7-opcua` | one node per variable, e.g. `"DB_Telemetria"."GoodCount"` |
| S7 PUT/GET | port 1102 (102 on a real CPU), rack 0, slot 1 | `DB1`, 26 bytes, non-optimized |

## DB_Telemetria (DB1)

| Offset | Variable | Type | Meaning |
|---|---|---|---|
| 0.0 | `Running` | BOOL | machine producing |
| 0.1 | `Fault` | BOOL | machine stopped by a fault |
| 2 | `FaultCode` | INT | fault code, 0 when there is none |
| 4 | `GoodCount` | DINT | good bags since the counter was last reset (wraps) |
| 8 | `RejectCount` | DINT | bags rejected by the check weigher |
| 12 | `MotorCurrent` | REAL | motor current, A |
| 16 | `Speed` | REAL | bags per minute |
| 20 | `CycleTime` | REAL | last cycle, s |
| 24 | `MotorTempRaw` | INT | motor temperature as the analog card reads it, 0..27648 for 0..150 °C |

`MotorTempRaw` is left unscaled on purpose: the gateway scales it, like the NORM_X/SCALE_X pair in the TCC's
ladder program would.

## Run

```bash
pip install -r requirements-dev.txt
python -m plc_sim --name ENS-01 --seed 1 --opcua-port 4840 --s7-port 1102
python -m pytest
```

Options are also read from `PLC_NAME`, `PLC_SEED`, `PLC_OPCUA_PORT`, `PLC_S7_PORT`, `PLC_OPCUA_USER`,
`PLC_OPCUA_PASSWORD` and `PLC_SCAN_MS`. With a user set, the OPC UA server refuses anonymous sessions.
