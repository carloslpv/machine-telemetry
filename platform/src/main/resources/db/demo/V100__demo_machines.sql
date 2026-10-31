-- Demo: the two bagging machines of plc-sim, one read over OPC UA and one over S7, and one silo conveyor that
-- reports over LoRaWAN. Loaded only with the demo profile.
INSERT INTO machine (id, site, name, ideal_cycle_seconds, stale_after_seconds, lorawan_dev_eui, lorawan_port, lorawan_analogs)
VALUES ('ENS-01', 'joacaba', 'Ensacadeira 1 (OPC UA)', 6.0, 120, NULL, NULL, '{}'),
       ('ENS-02', 'joacaba', 'Ensacadeira 2 (S7)', 6.0, 120, NULL, NULL, '{}'),
       ('ENS-03', 'joacaba', 'Ensacadeira 3 (LoRaWAN)', 6.0, 900, '70B3D57ED0000001', 1, '{motor-current,motor-temp}');

INSERT INTO fault_code (machine_id, code, description)
SELECT m.id, f.code, f.description
FROM machine m
         CROSS JOIN (VALUES (101, 'Balança fora de tolerância'),
                            (102, 'Saco travado na boca de ensaque'),
                            (103, 'Falta de produto no silo pulmão'),
                            (201, 'Sobrecarga do motor'),
                            (301, 'Emergência acionada')) AS f(code, description);
