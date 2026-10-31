package dev.carloslpv.machinetelemetry.platform.api;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import dev.carloslpv.machinetelemetry.platform.PlatformProperties;

/**
 * Read side: machines with their current state, OEE and stops for a period (today by default), and the two writes
 * a deployment needs, registering a machine and naming its fault codes. All numbers come from the SQL functions in
 * V2__oee.sql, the same ones the Grafana dashboard uses.
 */
@RestController
@RequestMapping("/api/v1/machines")
class MachineController {

	private final JdbcClient jdbc;

	private final PlatformProperties properties;

	MachineController(JdbcClient jdbc, PlatformProperties properties) {
		this.jdbc = jdbc;
		this.properties = properties;
	}

	record MachineView(String id, String site, String name, BigDecimal idealCycleSeconds, String state, Integer faultCode,
			Instant since, Instant lastSeenAt, boolean stale) {
	}

	record OeeView(String machineId, Instant from, Instant to, double runningSeconds, double idleSeconds, double faultSeconds,
			double noDataSeconds, long good, long reject, Double availability, Double performance, Double quality, Double oee) {
	}

	record StopView(Instant startedAt, Instant endedAt, double seconds, String state, int faultCode, String description) {
	}

	record ParetoView(String state, int faultCode, String description, long stops, double seconds) {
	}

	record MachineRequest(String site, String name, BigDecimal idealCycleSeconds, Integer staleAfterSeconds, LoraWan lorawan) {
	}

	record LoraWan(String devEui, Integer port, List<String> analogs) {
	}

	record FaultCodeRequest(String description) {
	}

	@GetMapping
	List<MachineView> machines() {
		return jdbc.sql("""
				SELECT m.id, m.site, m.name, m.ideal_cycle_seconds, si.state, si.fault_code, si.started_at, m.last_seen_at,
				       m.last_seen_at IS NULL OR m.last_seen_at < now() - make_interval(secs => m.stale_after_seconds) AS stale
				FROM machine m LEFT JOIN state_interval si ON si.machine_id = m.id AND si.ended_at IS NULL
				ORDER BY m.site, m.id""")
			.query((rs, i) -> new MachineView(rs.getString(1), rs.getString(2), rs.getString(3), rs.getBigDecimal(4), rs.getString(5),
					(Integer) rs.getObject(6), instant(rs.getTimestamp(7)), instant(rs.getTimestamp(8)), rs.getBoolean(9)))
			.list();
	}

	@GetMapping("/{id}/oee")
	OeeView oee(@PathVariable String id, @RequestParam(required = false) Instant from, @RequestParam(required = false) Instant to) {
		requireMachine(id);
		Instant[] window = window(from, to);
		return jdbc.sql("SELECT * FROM machine_oee(:id, :from, :to)")
			.param("id", id)
			.param("from", Timestamp.from(window[0]))
			.param("to", Timestamp.from(window[1]))
			.query((rs, i) -> new OeeView(id, window[0], window[1], rs.getDouble("running_seconds"), rs.getDouble("idle_seconds"),
					rs.getDouble("fault_seconds"), rs.getDouble("no_data_seconds"), rs.getLong("good"), rs.getLong("reject"),
					(Double) rs.getObject("availability"), (Double) rs.getObject("performance"), (Double) rs.getObject("quality"),
					(Double) rs.getObject("oee")))
			.single();
	}

	@GetMapping("/{id}/stops")
	List<StopView> stops(@PathVariable String id, @RequestParam(required = false) Instant from, @RequestParam(required = false) Instant to) {
		requireMachine(id);
		Instant[] window = window(from, to);
		return jdbc.sql("SELECT * FROM machine_stops(:id, :from, :to)")
			.param("id", id)
			.param("from", Timestamp.from(window[0]))
			.param("to", Timestamp.from(window[1]))
			.query((rs, i) -> new StopView(instant(rs.getTimestamp(1)), instant(rs.getTimestamp(2)), rs.getDouble(3), rs.getString(4),
					rs.getInt(5), rs.getString(6)))
			.list();
	}

	/** Stops grouped by cause, longest total first: where to look first to win availability back. */
	@GetMapping("/{id}/stops/pareto")
	List<ParetoView> pareto(@PathVariable String id, @RequestParam(required = false) Instant from, @RequestParam(required = false) Instant to) {
		requireMachine(id);
		Instant[] window = window(from, to);
		return jdbc.sql("""
				SELECT state, fault_code, description, count(*), sum(seconds) AS total
				FROM machine_stops(:id, :from, :to)
				GROUP BY state, fault_code, description ORDER BY total DESC""")
			.param("id", id)
			.param("from", Timestamp.from(window[0]))
			.param("to", Timestamp.from(window[1]))
			.query((rs, i) -> new ParetoView(rs.getString(1), rs.getInt(2), rs.getString(3), rs.getLong(4), rs.getDouble(5)))
			.list();
	}

	@PutMapping("/{id}")
	@Transactional
	Map<String, String> register(@PathVariable String id, @RequestBody MachineRequest request) {
		if (!id.matches("[A-Za-z0-9_-]{1,64}") || request.site() == null || !request.site().matches("[A-Za-z0-9_-]{1,64}")
				|| request.name() == null || request.idealCycleSeconds() == null || request.idealCycleSeconds().signum() <= 0) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "id, site, name and a positive idealCycleSeconds are required");
		}
		LoraWan lora = request.lorawan();
		jdbc.sql("""
				INSERT INTO machine (id, site, name, ideal_cycle_seconds, stale_after_seconds, lorawan_dev_eui, lorawan_port, lorawan_analogs)
				VALUES (:id, :site, :name, :cycle, :stale, :eui, :port, :analogs)
				ON CONFLICT (id) DO UPDATE SET site = excluded.site, name = excluded.name, ideal_cycle_seconds = excluded.ideal_cycle_seconds,
				  stale_after_seconds = excluded.stale_after_seconds, lorawan_dev_eui = excluded.lorawan_dev_eui,
				  lorawan_port = excluded.lorawan_port, lorawan_analogs = excluded.lorawan_analogs""")
			.param("id", id)
			.param("site", request.site())
			.param("name", request.name())
			.param("cycle", request.idealCycleSeconds())
			.param("stale", request.staleAfterSeconds() == null ? (lora == null ? 120 : 900) : request.staleAfterSeconds())
			.param("eui", lora == null || lora.devEui() == null ? null : lora.devEui().toUpperCase())
			.param("port", lora == null ? null : lora.port())
			.param("analogs", lora == null || lora.analogs() == null ? new String[0] : lora.analogs().toArray(String[]::new))
			.update();
		return Map.of("id", id);
	}

	@PutMapping("/{id}/fault-codes/{code}")
	Map<String, Object> faultCode(@PathVariable String id, @PathVariable int code, @RequestBody FaultCodeRequest request) {
		requireMachine(id);
		jdbc.sql("""
				INSERT INTO fault_code (machine_id, code, description) VALUES (:id, :code, :d)
				ON CONFLICT (machine_id, code) DO UPDATE SET description = excluded.description""")
			.param("id", id)
			.param("code", code)
			.param("d", request.description())
			.update();
		return Map.of("machineId", id, "code", code);
	}

	private void requireMachine(String id) {
		if (jdbc.sql("SELECT count(*) FROM machine WHERE id = :id").param("id", id).query(Integer.class).single() == 0) {
			throw new ResponseStatusException(HttpStatus.NOT_FOUND, "no machine " + id);
		}
	}

	private Instant[] window(Instant from, Instant to) {
		Instant end = to == null ? Instant.now() : to;
		Instant start = from == null ? LocalDate.now(properties.zone()).atStartOfDay(properties.zone()).toInstant() : from;
		if (!start.isBefore(end)) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "from must be before to");
		}
		return new Instant[] { start, end };
	}

	private static Instant instant(Timestamp ts) {
		return ts == null ? null : ts.toInstant();
	}
}
