"""Behaviour of the simulated machine: a feed bagging machine (ensacadeira) at a feed mill.

The model is deliberately simple but has the losses OEE is meant to expose:

* availability: short idle stops (waiting for the operator, no bags) and longer faults with a code;
* performance: the real cycle drifts slower than the ideal cycle;
* quality: some bags are rejected by the check weigher.

Everything is driven by ``step(dt)`` and a seeded random generator, so tests and the history backfill tool get
the same sequence every run.
"""

from __future__ import annotations

import enum
import math
import random
from dataclasses import dataclass, field

from . import layout


class State(enum.Enum):
    RUNNING = "RUNNING"
    IDLE = "IDLE"
    FAULT = "FAULT"


# codes the PLC program raises; the platform ships the same table so stops show a description
FAULTS: dict[int, str] = {
    101: "Balança fora de tolerância",
    102: "Saco travado na boca de ensaque",
    103: "Falta de produto no silo pulmão",
    201: "Sobrecarga do motor",
    301: "Emergência acionada",
}

# relative frequency of the faults that happen at random (201 only comes from overheating)
_RANDOM_FAULTS = ((101, 3), (102, 5), (103, 4), (301, 1))


@dataclass
class Settings:
    ideal_cycle_s: float = 6.0
    reject_rate: float = 0.02
    mean_s_between_idle: float = 420.0
    idle_s: tuple[float, float] = (20.0, 120.0)
    mean_s_between_faults: float = 2400.0
    fault_s: tuple[float, float] = (90.0, 600.0)
    ambient_c: float = 32.0
    running_temp_c: float = 71.0
    thermal_tau_s: float = 900.0
    overload_temp_c: float = 88.0


@dataclass
class Machine:
    seed: int = 1
    settings: Settings = field(default_factory=Settings)

    def __post_init__(self) -> None:
        self._rng = random.Random(self.seed)
        self.state = State.RUNNING
        self.fault_code = 0
        self.good_count = 0
        self.reject_count = 0
        self.motor_temp = self.settings.ambient_c + 20.0
        self.motor_current = 0.0
        self.speed_factor = 0.92
        self.last_cycle_s = self.settings.ideal_cycle_s
        self._cycle_progress = 0.0
        self._cycle_elapsed = 0.0
        self._state_left = 0.0
        # slow component of the motor load, so temperature and current wander instead of being flat
        self._load_phase = self._rng.uniform(0, 2 * math.pi)
        self._time = 0.0

    def step(self, dt: float) -> None:
        self._time += dt
        if self.state is State.RUNNING:
            self._run(dt)
        else:
            self._state_left -= dt
            if self._state_left <= 0:
                self._enter(State.RUNNING)
        self._update_motor(dt)

    def _run(self, dt: float) -> None:
        s = self.settings
        # random walk of the real speed between 78 % and 100 % of the ideal one
        self.speed_factor += self._rng.gauss(0, 0.004) * math.sqrt(dt)
        self.speed_factor = min(1.0, max(0.78, self.speed_factor + (0.9 - self.speed_factor) * 0.001 * dt))
        self._cycle_elapsed += dt
        self._cycle_progress += dt * self.speed_factor / s.ideal_cycle_s
        while self._cycle_progress >= 1.0:
            self._cycle_progress -= 1.0
            self.last_cycle_s = self._cycle_elapsed
            self._cycle_elapsed = 0.0
            if self._rng.random() < s.reject_rate:
                self.reject_count += 1
            else:
                self.good_count += 1

        if self.motor_temp >= s.overload_temp_c:
            self._enter(State.FAULT, 201)
        elif self._rng.random() < dt / s.mean_s_between_faults:
            codes, weights = zip(*_RANDOM_FAULTS)
            self._enter(State.FAULT, self._rng.choices(codes, weights)[0])
        elif self._rng.random() < dt / s.mean_s_between_idle:
            self._enter(State.IDLE)

    def _enter(self, state: State, fault_code: int = 0) -> None:
        s = self.settings
        self.state = state
        self.fault_code = fault_code
        if state is State.IDLE:
            self._state_left = self._rng.uniform(*s.idle_s)
        elif state is State.FAULT:
            self._state_left = self._rng.uniform(*s.fault_s)
        else:
            self._cycle_elapsed = 0.0

    def _update_motor(self, dt: float) -> None:
        s = self.settings
        load = 0.5 + 0.5 * math.sin(self._time / 1800.0 + self._load_phase)  # 0..1 over about three hours
        if self.state is State.RUNNING:
            target = s.running_temp_c + 14.0 * load
            self.motor_current = 7.2 * self.speed_factor + 1.4 * load + self._rng.gauss(0, 0.15)
        else:
            target = s.ambient_c
            self.motor_current = 0.6 + self._rng.gauss(0, 0.05) if self.state is State.IDLE else 0.0
        self.motor_temp += (target - self.motor_temp) * (1 - math.exp(-dt / s.thermal_tau_s))
        self.motor_temp += self._rng.gauss(0, 0.02)

    def speed_per_min(self) -> float:
        if self.state is not State.RUNNING:
            return 0.0
        return 60.0 * self.speed_factor / self.settings.ideal_cycle_s

    def plc_values(self) -> dict[str, object]:
        """The machine as the PLC program writes it into DB_Telemetria."""
        return {
            "Running": self.state is State.RUNNING,
            "Fault": self.state is State.FAULT,
            "FaultCode": self.fault_code,
            # DINT counters wrap like the PLC's would
            "GoodCount": _to_dint(self.good_count),
            "RejectCount": _to_dint(self.reject_count),
            "MotorCurrent": max(0.0, self.motor_current),
            "Speed": self.speed_per_min(),
            "CycleTime": self.last_cycle_s,
            "MotorTempRaw": layout.temperature_to_raw(self.motor_temp),
        }


def _to_dint(value: int) -> int:
    value &= 0xFFFFFFFF
    return value - (1 << 32) if value >= 1 << 31 else value
