"""Layout of DB_Telemetria, the data block the PLC program exposes.

The same block is reachable two ways, as on a real S7-1200:

* over S7 PUT/GET, as raw bytes of DB1 (the block must have "Optimized block access" turned off, otherwise
  the offsets below do not exist);
* over the CPU's OPC UA server, one node per variable, in the namespace Siemens uses for PLC tags.

Offsets follow TIA Portal's rules for a non-optimized block: BOOLs packed in a byte, INT/DINT/REAL aligned to
even addresses, big-endian.
"""

from __future__ import annotations

import struct
from dataclasses import dataclass

DB_NUMBER = 1
DB_NAME = "DB_Telemetria"
DB_SIZE = 26

# the namespace URI the S7-1200/S7-1500 OPC UA server uses for PLC variables, so a gateway configured against
# this simulator works against a real CPU without changes
SIEMENS_NAMESPACE_URI = "http://www.siemens.com/simatic-s7-opcua"

# full scale of the analog input after NORM_X, as in the TCC's ladder program
ANALOG_FULL_SCALE = 27648
# the temperature transmitter maps 0..150 °C to the full scale of the input
MOTOR_TEMP_RANGE = (0.0, 150.0)


@dataclass(frozen=True)
class Variable:
    name: str
    s7_type: str  # BOOL, INT, DINT, REAL
    byte: int
    bit: int = 0

    def node_id(self) -> str:
        """String identifier the S7 OPC UA server gives a DB variable: "DB"."Var"."""
        return f'"{DB_NAME}"."{self.name}"'


VARIABLES: tuple[Variable, ...] = (
    Variable("Running", "BOOL", 0, 0),
    Variable("Fault", "BOOL", 0, 1),
    Variable("FaultCode", "INT", 2),
    Variable("GoodCount", "DINT", 4),
    Variable("RejectCount", "DINT", 8),
    Variable("MotorCurrent", "REAL", 12),
    Variable("Speed", "REAL", 16),
    Variable("CycleTime", "REAL", 20),
    Variable("MotorTempRaw", "INT", 24),
)

BY_NAME = {v.name: v for v in VARIABLES}

_FORMATS = {"INT": ">h", "DINT": ">i", "REAL": ">f"}


def write(buffer: bytearray, values: dict[str, object]) -> None:
    """Encodes the values into the DB image in place."""
    for name, value in values.items():
        var = BY_NAME[name]
        if var.s7_type == "BOOL":
            mask = 1 << var.bit
            if value:
                buffer[var.byte] |= mask
            else:
                buffer[var.byte] &= ~mask & 0xFF
        else:
            struct.pack_into(_FORMATS[var.s7_type], buffer, var.byte, value)


def read(buffer: bytes | bytearray) -> dict[str, object]:
    """Decodes the DB image, the inverse of write()."""
    out: dict[str, object] = {}
    for var in VARIABLES:
        if var.s7_type == "BOOL":
            out[var.name] = bool(buffer[var.byte] >> var.bit & 1)
        else:
            out[var.name] = struct.unpack_from(_FORMATS[var.s7_type], buffer, var.byte)[0]
    return out


def temperature_to_raw(celsius: float) -> int:
    """What the analog input reads for a temperature, clamped to the input range like the real card."""
    low, high = MOTOR_TEMP_RANGE
    raw = round((celsius - low) / (high - low) * ANALOG_FULL_SCALE)
    return max(0, min(ANALOG_FULL_SCALE, raw))
