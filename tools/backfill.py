"""Fills the platform with some hours of history, for a demo or a screenshot.

Runs the plc-sim machine model offline for each machine and publishes, over MQTT, exactly what the gateway would
have published (state changes as they happen, telemetry every 10 s), with past timestamps. It is the same thing the
gateway does after a network outage, just longer. Run it before starting the gateway: the platform drops messages
older than the last one it has seen for a machine.

    pip install paho-mqtt==2.1.0
    python tools/backfill.py --hours 8 --password "$MQTT_ADMIN_PASSWORD"
"""

from __future__ import annotations

import argparse
import json
import os
import sys
from datetime import datetime, timedelta, timezone

import paho.mqtt.client as mqtt

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "plc-sim"))

from plc_sim import layout  # noqa: E402
from plc_sim.machine import Machine  # noqa: E402

STEP = 0.25
WINDOW = 10.0


def iso(t: datetime) -> str:
    return t.isoformat(timespec="milliseconds").replace("+00:00", "Z")


def history(machine_id: str, seed: int, start: datetime, hours: float):
    m = Machine(seed=seed)
    t, state, code = 0.0, None, 0
    window: dict[str, list[float]] = {"motor-current": [], "motor-temp": [], "speed": []}
    next_window = WINDOW
    while t < hours * 3600:
        m.step(STEP)
        t += STEP
        now = start + timedelta(seconds=t)
        values = m.plc_values()
        new_state = "FAULT" if values["Fault"] else "RUNNING" if values["Running"] else "IDLE"
        new_code = values["FaultCode"] if values["Fault"] else 0
        if (new_state, new_code) != (state, code):
            yield "state", {"machineId": machine_id, "at": iso(now), "state": new_state, "faultCode": new_code,
                            "previousState": state, "previousFaultCode": code}
            state, code = new_state, new_code
        window["motor-current"].append(values["MotorCurrent"])
        window["speed"].append(values["Speed"])
        low, high = layout.MOTOR_TEMP_RANGE
        window["motor-temp"].append(low + values["MotorTempRaw"] * (high - low) / layout.ANALOG_FULL_SCALE)
        if t >= next_window:
            yield "telemetry", {
                "machineId": machine_id, "at": iso(now), "state": state, "faultCode": code, "windowSeconds": WINDOW,
                "goodCount": values["GoodCount"] & 0xFFFFFFFF, "rejectCount": values["RejectCount"] & 0xFFFFFFFF,
                "counterBits": 32,
                "analogs": {k: {"avg": round(sum(v) / len(v), 3), "min": round(min(v), 3), "max": round(max(v), 3),
                                "last": round(v[-1], 3), "samples": len(v)} for k, v in window.items() if v},
            }
            window = {k: [] for k in window}
            next_window += WINDOW


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--hours", type=float, default=8)
    parser.add_argument("--host", default="localhost")
    parser.add_argument("--port", type=int, default=1883)
    parser.add_argument("--user", default="admin")
    parser.add_argument("--password", required=True)
    parser.add_argument("--site", default="joacaba")
    parser.add_argument("--machines", default="ENS-01:11,ENS-02:12", help="id:seed,...")
    args = parser.parse_args()

    client = mqtt.Client(mqtt.CallbackAPIVersion.VERSION2, client_id="backfill")
    client.username_pw_set(args.user, args.password)
    client.max_queued_messages_set(0)
    client.connect(args.host, args.port)
    client.loop_start()

    start = datetime.now(timezone.utc) - timedelta(hours=args.hours)
    seq = 0
    for spec in args.machines.split(","):
        machine_id, seed = spec.split(":")
        count = 0
        for kind, body in history(machine_id, int(seed), start, args.hours):
            seq += 1
            message = {"seq": seq, "gatewayId": "backfill", **body}
            client.publish(f"mt/{args.site}/{machine_id}/{kind}", json.dumps(message), qos=1).wait_for_publish()
            count += 1
        print(f"{machine_id}: {count} messages from {iso(start)}")
    client.loop_stop()
    client.disconnect()


if __name__ == "__main__":
    main()
