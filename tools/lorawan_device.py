"""A machine on a LoRaWAN link, as the platform sees it: posts what The Things Network's webhook would post.

It runs the plc-sim machine model (seed 3), and sends an uplink when the state changes (never closer than six
seconds, the limit the TCC found for its module) and every UPLINK_INTERVAL_S otherwise. RSSI and SNR are made up
around the values the TCC measured at about 280 m.
"""

from __future__ import annotations

import base64
import json
import os
import random
import secrets
import sys
import time
import urllib.error
import urllib.request
from datetime import datetime, timezone

sys.path.insert(0, os.path.dirname(__file__))

from payload import encode  # noqa: E402
from plc_sim.machine import Machine  # noqa: E402

URL = os.environ["WEBHOOK_URL"]
TOKEN = os.environ["TTN_WEBHOOK_TOKEN"]
APP = os.environ.get("TTN_APPLICATION_ID", "tcc-carlos")
DEV_EUI = os.environ.get("DEV_EUI", "70B3D57ED0000001")
INTERVAL = float(os.environ.get("UPLINK_INTERVAL_S", "300"))
MIN_INTERVAL = 6.0


def uplink(machine: Machine, since: float, f_cnt: int, session: str) -> dict:
    now = datetime.now(timezone.utc).isoformat().replace("+00:00", "Z")
    payload = encode(machine.state.value, machine.fault_code, machine.good_count, machine.reject_count,
                     time.monotonic() - since, [machine.motor_current, machine.motor_temp])
    return {
        "end_device_ids": {"device_id": "ens-03", "application_ids": {"application_id": APP}, "dev_eui": DEV_EUI},
        "received_at": now,
        "uplink_message": {
            "session_key_id": session,
            "f_port": 1,
            "f_cnt": f_cnt,
            "frm_payload": base64.b64encode(payload).decode(),
            "received_at": now,
            "rx_metadata": [{"gateway_ids": {"gateway_id": "lps8n-indoor"},
                             "rssi": round(random.gauss(-96, 3)), "snr": round(random.gauss(5, 2), 1)}],
        },
    }


def post(body: dict) -> str:
    request = urllib.request.Request(URL, data=json.dumps(body).encode(), method="POST",
                                     headers={"Content-Type": "application/json", "X-Webhook-Token": TOKEN})
    with urllib.request.urlopen(request, timeout=10) as response:
        return json.loads(response.read()).get("result", "?")


def main() -> None:
    machine = Machine(seed=3)
    session = base64.b64encode(secrets.token_bytes(6)).decode()
    f_cnt = 0
    state = machine.state
    since = last_sent = time.monotonic()
    pending = True
    previous = time.monotonic()
    while True:
        time.sleep(0.5)
        now = time.monotonic()
        machine.step(now - previous)
        previous = now
        if machine.state != state:
            state, since, pending = machine.state, now, True
        due = pending or now - last_sent >= INTERVAL
        if due and now - last_sent >= MIN_INTERVAL:
            f_cnt += 1
            try:
                result = post(uplink(machine, since, f_cnt, session))
                print(f"uplink {f_cnt}: {machine.state.value} -> {result}", flush=True)
                pending = False
            except (urllib.error.URLError, OSError) as ex:
                print(f"uplink {f_cnt} failed: {ex}", flush=True)  # lost, like a LoRa frame; the next one has the totals
            last_sent = now


if __name__ == "__main__":
    main()
