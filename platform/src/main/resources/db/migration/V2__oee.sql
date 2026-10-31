-- The OEE rules live here, in the database, so the REST API and the Grafana dashboard compute the same numbers.

-- Seconds per state inside [p_from, p_to). An interval still open ends now, or when the machine went quiet:
-- last message + stale_after_seconds. The time after that is not counted as anything (it is a gap, not running).
CREATE FUNCTION machine_state_seconds(p_machine text, p_from timestamptz, p_to timestamptz)
    RETURNS TABLE (state text, seconds double precision)
    LANGUAGE sql STABLE AS
$$
SELECT si.state,
       sum(extract(epoch FROM least(coalesce(si.ended_at, open_end.at), p_to) - greatest(si.started_at, p_from)))
FROM state_interval si
         JOIN machine m ON m.id = si.machine_id
         CROSS JOIN LATERAL (
    SELECT greatest(si.started_at,
                    least(now(), coalesce(m.last_seen_at, si.started_at) + make_interval(secs => m.stale_after_seconds))) AS at
    ) open_end
WHERE si.machine_id = p_machine
  AND si.started_at < p_to
  AND coalesce(si.ended_at, open_end.at) > p_from
GROUP BY si.state
$$;

-- OEE = availability x performance x quality, over the time the platform has data for.
--   availability = running / (running + idle + fault)
--   performance  = units x ideal cycle / running   (capped at 1: a faster than ideal cycle means the ideal is wrong)
--   quality      = good / (good + reject)
-- Without a shift calendar every second with data counts as planned production time, so idle time is a loss.
CREATE FUNCTION machine_oee(p_machine text, p_from timestamptz, p_to timestamptz)
    RETURNS TABLE (
        running_seconds  double precision,
        idle_seconds     double precision,
        fault_seconds    double precision,
        no_data_seconds  double precision,
        good             bigint,
        reject           bigint,
        availability     double precision,
        performance      double precision,
        quality          double precision,
        oee              double precision
    )
    LANGUAGE sql STABLE AS
$$
WITH t AS (
    SELECT coalesce(sum(seconds) FILTER (WHERE state = 'RUNNING'), 0) AS running,
           coalesce(sum(seconds) FILTER (WHERE state = 'IDLE'), 0)    AS idle,
           coalesce(sum(seconds) FILTER (WHERE state = 'FAULT'), 0)   AS fault,
           coalesce(sum(seconds) FILTER (WHERE state = 'NO_DATA'), 0) AS no_data
    FROM machine_state_seconds(p_machine, p_from, p_to)
),
p AS (
    SELECT coalesce(sum(good), 0)::bigint AS good, coalesce(sum(reject), 0)::bigint AS reject
    FROM production
    WHERE machine_id = p_machine AND recorded_at >= p_from AND recorded_at < p_to
),
f AS (
    SELECT t.*, p.*,
           t.running / nullif(t.running + t.idle + t.fault, 0)                                         AS a,
           least(1.0, (p.good + p.reject) * m.ideal_cycle_seconds::double precision / nullif(t.running, 0)) AS perf,
           p.good::double precision / nullif(p.good + p.reject, 0)                                     AS q
    FROM t, p, machine m
    WHERE m.id = p_machine
)
SELECT running, idle, fault, no_data, good, reject, a, perf, q, a * perf * q
FROM f
$$;

-- Stops (idle and fault intervals) clipped to the window, with the fault description when there is one.
CREATE FUNCTION machine_stops(p_machine text, p_from timestamptz, p_to timestamptz)
    RETURNS TABLE (
        started_at  timestamptz,
        ended_at    timestamptz,
        seconds     double precision,
        state       text,
        fault_code  integer,
        description text
    )
    LANGUAGE sql STABLE AS
$$
SELECT si.started_at,
       si.ended_at,
       extract(epoch FROM least(coalesce(si.ended_at, now()), p_to) - greatest(si.started_at, p_from)),
       si.state,
       si.fault_code,
       CASE WHEN si.state = 'IDLE' THEN 'Parada sem falha' ELSE coalesce(fc.description, 'Falha ' || si.fault_code) END
FROM state_interval si
         LEFT JOIN fault_code fc ON fc.machine_id = si.machine_id AND fc.code = si.fault_code
WHERE si.machine_id = p_machine
  AND si.state IN ('IDLE', 'FAULT')
  AND si.started_at < p_to
  AND coalesce(si.ended_at, now()) > p_from
ORDER BY si.started_at
$$;
