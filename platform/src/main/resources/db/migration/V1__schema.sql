-- Machines and what the platform remembers about each one between messages.
CREATE TABLE machine (
    id                  text PRIMARY KEY,
    site                text NOT NULL,
    name                text NOT NULL,
    -- time one unit should take at nominal speed; the performance factor of OEE is measured against it
    ideal_cycle_seconds numeric(10, 3) NOT NULL CHECK (ideal_cycle_seconds > 0),
    -- without a message for this long the machine counts as NO_DATA (10 s telemetry over MQTT, 5 min over LoRaWAN)
    stale_after_seconds integer NOT NULL DEFAULT 120 CHECK (stale_after_seconds > 0),
    lorawan_dev_eui     text,
    lorawan_port        integer CHECK (lorawan_port BETWEEN 1 AND 223),
    lorawan_analogs     text[] NOT NULL DEFAULT '{}',
    last_seen_at        timestamptz,
    last_good_raw       bigint,
    last_reject_raw     bigint,
    counter_bits        integer,
    created_at          timestamptz NOT NULL DEFAULT now(),
    UNIQUE (lorawan_dev_eui, lorawan_port)
);

CREATE TABLE fault_code (
    machine_id  text NOT NULL REFERENCES machine ON DELETE CASCADE,
    code        integer NOT NULL,
    description text NOT NULL,
    PRIMARY KEY (machine_id, code)
);

-- The state history as intervals: one row per period the machine spent in a state (and fault code).
-- Durations, stops and availability all come from here.
CREATE TABLE state_interval (
    id         bigserial PRIMARY KEY,
    machine_id text NOT NULL REFERENCES machine ON DELETE CASCADE,
    state      text NOT NULL CHECK (state IN ('RUNNING', 'IDLE', 'FAULT', 'NO_DATA')),
    fault_code integer NOT NULL DEFAULT 0,
    started_at timestamptz NOT NULL,
    ended_at   timestamptz,
    CHECK (ended_at IS NULL OR ended_at >= started_at)
);
-- at most one open interval per machine: the current state
CREATE UNIQUE INDEX state_interval_open ON state_interval (machine_id) WHERE ended_at IS NULL;
CREATE INDEX state_interval_machine_time ON state_interval (machine_id, started_at);

-- Units produced, as deltas of the PLC counters, at the end of each telemetry window.
CREATE TABLE production (
    machine_id  text NOT NULL REFERENCES machine ON DELETE CASCADE,
    recorded_at timestamptz NOT NULL,
    good        integer NOT NULL CHECK (good >= 0),
    reject      integer NOT NULL CHECK (reject >= 0)
);
CREATE INDEX production_machine_time ON production (machine_id, recorded_at);

CREATE TABLE analog_sample (
    machine_id  text NOT NULL REFERENCES machine ON DELETE CASCADE,
    name        text NOT NULL,
    recorded_at timestamptz NOT NULL,
    avg         double precision NOT NULL,
    min         double precision NOT NULL,
    max         double precision NOT NULL
);
CREATE INDEX analog_sample_machine_name_time ON analog_sample (machine_id, name, recorded_at);

-- Link quality of every LoRaWAN uplink, the RSSI analysis of the TCC kept for each device.
CREATE TABLE lorawan_uplink (
    machine_id  text NOT NULL REFERENCES machine ON DELETE CASCADE,
    received_at timestamptz NOT NULL,
    f_cnt       bigint,
    rssi        integer,
    snr         real,
    gateway_id  text
);
CREATE INDEX lorawan_uplink_machine_time ON lorawan_uplink (machine_id, received_at);

-- Messages already applied, so a redelivery (MQTT QoS 1, a gateway that resent after a crash, TTN retrying a
-- webhook) changes nothing. source is the gateway id or the LoRaWAN device; key is its sequence or counter.
CREATE TABLE received_message (
    source      text NOT NULL,
    message_key text NOT NULL,
    received_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (source, message_key)
);
CREATE INDEX received_message_age ON received_message (received_at);

CREATE TABLE gateway (
    id         text PRIMARY KEY,
    site       text NOT NULL,
    status     text NOT NULL,
    changed_at timestamptz NOT NULL
);
