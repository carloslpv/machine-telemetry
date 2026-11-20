-- Demo: a machine without a PLC, watched by the ESP32 node in esp32-node (a clamp-on current sensor on the motor
-- cable). It reports a state change at once and telemetry every 10 s, so 60 s without a message means no data.
INSERT INTO machine (id, site, name, ideal_cycle_seconds, stale_after_seconds, lorawan_dev_eui, lorawan_port, lorawan_analogs)
VALUES ('MAQ-01', 'maleta', 'Máquina sem CLP (ESP32 + sensor de corrente)', 6.0, 60, NULL, NULL, '{}');

INSERT INTO fault_code (machine_id, code, description)
VALUES ('MAQ-01', 201, 'Sobrecarga do motor');
