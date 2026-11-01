"""Uplink payload v1 in Python, for the tools. Same layout as the gateway's PayloadCodec."""

from __future__ import annotations

import struct

STATE_CODES = {"IDLE": 0, "RUNNING": 1, "FAULT": 2, "NO_DATA": 3}


def encode(state: str, fault_code: int, good: int, reject: int, seconds_in_state: float, analogs: list[float | None]) -> bytes:
    out = bytes([0x10 | STATE_CODES[state]])
    out += struct.pack(">HHHH", max(0, min(fault_code, 0xFFFF)), good & 0xFFFF, reject & 0xFFFF,
                       max(0, min(int(seconds_in_state), 0xFFFF)))
    for value in analogs:
        if value is None:
            out += struct.pack(">h", -32768)
        else:
            # round half away from zero, like Java's Math.round for positive values
            scaled = int(value * 10 + (0.5 if value >= 0 else -0.5))
            out += struct.pack(">h", max(-32767, min(32767, scaled)))
    return out
