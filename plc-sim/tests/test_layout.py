from plc_sim import layout


def test_round_trip():
    db = bytearray(layout.DB_SIZE)
    values = {
        "Running": True, "Fault": False, "FaultCode": 102, "GoodCount": 123456, "RejectCount": -2,
        "MotorCurrent": 7.5, "Speed": 9.25, "CycleTime": 6.5, "MotorTempRaw": 13824,
    }
    layout.write(db, values)
    assert layout.read(db) == values


def test_offsets_follow_a_non_optimized_block():
    db = bytearray(layout.DB_SIZE)
    layout.write(db, {"Fault": True, "FaultCode": 0x0102, "GoodCount": 1})
    assert db[0] == 0b10  # Fault is bit 0.1
    assert db[2:4] == b"\x01\x02"  # big-endian INT at 2
    assert db[4:8] == b"\x00\x00\x00\x01"  # DINT at 4


def test_bools_share_a_byte_without_clobbering_each_other():
    db = bytearray(layout.DB_SIZE)
    layout.write(db, {"Running": True, "Fault": True})
    layout.write(db, {"Running": False})
    assert layout.read(db)["Fault"] is True


def test_temperature_scaling_matches_the_analog_input():
    assert layout.temperature_to_raw(0) == 0
    assert layout.temperature_to_raw(75) == 13824
    assert layout.temperature_to_raw(150) == 27648
    assert layout.temperature_to_raw(400) == 27648  # the card saturates


def test_node_ids_use_the_siemens_syntax():
    assert layout.BY_NAME["Running"].node_id() == '"DB_Telemetria"."Running"'
