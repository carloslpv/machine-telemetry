from plc_sim.machine import FAULTS, Machine, State


def run(machine: Machine, seconds: float, dt: float = 0.1) -> dict[State, float]:
    time_in = {s: 0.0 for s in State}
    for _ in range(int(seconds / dt)):
        machine.step(dt)
        time_in[machine.state] += dt
    return time_in


def test_same_seed_same_history():
    a, b = Machine(seed=7), Machine(seed=7)
    run(a, 3600)
    run(b, 3600)
    assert (a.good_count, a.reject_count, a.state) == (b.good_count, b.reject_count, b.state)


def test_a_shift_has_the_losses_oee_should_show():
    m = Machine(seed=3)
    time_in = run(m, 8 * 3600)
    total = sum(time_in.values())
    availability = time_in[State.RUNNING] / total
    produced = m.good_count + m.reject_count
    performance = produced * m.settings.ideal_cycle_s / time_in[State.RUNNING]
    quality = m.good_count / produced
    assert 0.6 < availability < 0.97
    assert 0.78 < performance < 1.0
    assert 0.95 < quality < 1.0
    assert time_in[State.FAULT] > 0 and time_in[State.IDLE] > 0


def test_fault_code_is_set_only_while_faulted():
    m = Machine(seed=5)
    for _ in range(200_000):
        m.step(0.1)
        values = m.plc_values()
        if values["Fault"]:
            assert values["FaultCode"] in FAULTS
            assert not values["Running"]
        else:
            assert values["FaultCode"] == 0


def test_overheating_trips_the_motor_overload():
    m = Machine(seed=1)
    m.settings.overload_temp_c = 60.0
    seen = set()
    for _ in range(100_000):
        m.step(0.1)
        seen.add(m.fault_code)
    assert 201 in seen


def test_counters_wrap_like_a_dint():
    m = Machine(seed=1)
    m.good_count = 2**31
    assert m.plc_values()["GoodCount"] == -(2**31)
