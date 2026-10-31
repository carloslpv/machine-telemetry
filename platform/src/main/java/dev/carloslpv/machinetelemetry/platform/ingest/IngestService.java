package dev.carloslpv.machinetelemetry.platform.ingest;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import dev.carloslpv.machinetelemetry.platform.ingest.Messages.Analog;

/**
 * Applies what gateways report to the state history and the production tables.
 * <p>
 * Each message runs in one transaction that first locks the machine row, so messages of the same machine apply one at
 * a time and in order, and records the message key, so a redelivered message does nothing. Rules:
 * <ul>
 * <li>a state change closes the open interval and opens a new one;</li>
 * <li>a gap longer than the machine's {@code stale_after_seconds} between two messages becomes a NO_DATA interval
 * (the gateway lost power, or the site lost its link for longer than the outbox could cover);</li>
 * <li>telemetry whose state disagrees with the open interval corrects it, so a state change dropped by a full outbox
 * heals at the next window;</li>
 * <li>a message older than the last one seen is late and dropped: the gateway sends in order, so it can only be a
 * stale copy.</li>
 * </ul>
 */
@Service
public class IngestService {

	private static final Logger log = LoggerFactory.getLogger(IngestService.class);

	private static final Set<String> STATES = Set.of("RUNNING", "IDLE", "FAULT", "NO_DATA");

	public enum Result { APPLIED, DUPLICATE, LATE, REJECTED }

	private final JdbcClient jdbc;

	public IngestService(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	record MachineRow(String id, String site, Instant lastSeenAt, Long lastGoodRaw, Long lastRejectRaw, Integer counterBits,
			int staleAfterSeconds, List<String> lorawanAnalogs) {
	}

	record OpenInterval(long id, String state, int faultCode, Instant startedAt) {
	}

	/** A state change, wherever it came from. */
	public record StateEvent(String machineId, Instant at, String state, int faultCode) {
	}

	/** Counters and analogs at the end of a window. */
	public record TelemetryEvent(String machineId, Instant at, String state, int faultCode, Long goodCount, Long rejectCount,
			int counterBits, Map<String, Analog> analogs) {
	}

	@Transactional
	public Result applyState(String source, String key, String site, StateEvent event) {
		Optional<MachineRow> machine = lockMachine(event.machineId(), site);
		if (machine.isEmpty() || !STATES.contains(event.state())) {
			return Result.REJECTED;
		}
		if (!firstDelivery(source, key)) {
			return Result.DUPLICATE;
		}
		if (isLate(machine.get(), event.at())) {
			return Result.LATE;
		}
		state(machine.get(), event.at(), event.state(), event.faultCode());
		touch(event.machineId(), event.at());
		return Result.APPLIED;
	}

	@Transactional
	public Result applyTelemetry(String source, String key, String site, TelemetryEvent event) {
		Optional<MachineRow> machine = lockMachine(event.machineId(), site);
		if (machine.isEmpty() || !STATES.contains(event.state())) {
			return Result.REJECTED;
		}
		if (!firstDelivery(source, key)) {
			return Result.DUPLICATE;
		}
		if (isLate(machine.get(), event.at())) {
			return Result.LATE;
		}
		telemetry(machine.get(), event, true);
		touch(event.machineId(), event.at());
		return Result.APPLIED;
	}

	/**
	 * A LoRaWAN uplink carries both: the current state with how long it has lasted, and the counters. The state
	 * change is placed at {@code at - secondsInState}.
	 */
	@Transactional
	public Result applyLoraUplink(String devEui, int port, String key, Instant at, int secondsInState, StateEvent state,
			TelemetryEvent telemetry, Integer rssi, Float snr, Long fCnt, String gatewayId) {
		Optional<String> machineId = jdbc.sql("SELECT id FROM machine WHERE lorawan_dev_eui = :eui AND lorawan_port = :port")
			.param("eui", devEui)
			.param("port", port)
			.query(String.class)
			.optional();
		if (machineId.isEmpty()) {
			return Result.REJECTED;
		}
		MachineRow machine = lockMachine(machineId.get(), null).orElseThrow();
		if (!firstDelivery("lorawan:" + devEui, key)) {
			return Result.DUPLICATE;
		}
		if (isLate(machine, at)) {
			return Result.LATE;
		}
		// a saturated counter (65535 s, about 18 h) says "for a long time", not when; then the uplink time is used
		Instant since = secondsInState >= 0xFFFF ? at : at.minusSeconds(secondsInState);
		if (machine.lastSeenAt() != null && since.isBefore(machine.lastSeenAt())) {
			since = machine.lastSeenAt();
		}
		state(machine, since, state.state(), state.faultCode());
		// the gap, if any, was handled with the state: the time in state already says the machine was observed
		telemetry(machine, telemetry, false);
		touch(machine.id(), at);
		jdbc.sql("""
				INSERT INTO lorawan_uplink (machine_id, received_at, f_cnt, rssi, snr, gateway_id)
				VALUES (:machine, :at, :fcnt, :rssi, :snr, :gw)""")
			.param("machine", machine.id())
			.param("at", Timestamp.from(at))
			.param("fcnt", fCnt)
			.param("rssi", rssi)
			.param("snr", snr)
			.param("gw", gatewayId)
			.update();
		return Result.APPLIED;
	}

	/** Machine ids of the LoRaWAN analog values, in payload order. */
	public List<String> lorawanAnalogs(String devEui, int port) {
		return jdbc.sql("SELECT lorawan_analogs FROM machine WHERE lorawan_dev_eui = :eui AND lorawan_port = :port")
			.param("eui", devEui)
			.param("port", port)
			.query((rs, i) -> List.of((String[]) rs.getArray(1).getArray()))
			.optional()
			.orElse(List.of());
	}

	@Transactional
	public void gatewayStatus(String site, Messages.GatewayStatus status) {
		jdbc.sql("""
				INSERT INTO gateway (id, site, status, changed_at) VALUES (:id, :site, :status, :at)
				ON CONFLICT (id) DO UPDATE SET site = excluded.site, status = excluded.status, changed_at = excluded.changed_at
				WHERE gateway.changed_at <= excluded.changed_at""")
			.param("id", status.gatewayId())
			.param("site", site)
			.param("status", status.status())
			.param("at", Timestamp.from(status.at()))
			.update();
	}

	private void state(MachineRow machine, Instant at, String state, int faultCode) {
		Optional<OpenInterval> open = closeGap(machine, at);
		if (open.isPresent() && open.get().state().equals(state) && open.get().faultCode() == faultCode) {
			return;
		}
		open.ifPresent(o -> close(o, at));
		openInterval(machine.id(), state, faultCode, at);
	}

	private void telemetry(MachineRow machine, TelemetryEvent t, boolean checkGap) {
		Optional<OpenInterval> open = checkGap ? closeGap(machine, t.at()) : openInterval(machine.id());
		if (open.isEmpty()) {
			openInterval(machine.id(), t.state(), t.faultCode(), t.at());
		}
		else if (!open.get().state().equals(t.state()) || open.get().faultCode() != t.faultCode()) {
			log.info("{}: telemetry says {} but the open interval is {}; correcting", machine.id(), t.state(), open.get().state());
			close(open.get(), t.at());
			openInterval(machine.id(), t.state(), t.faultCode(), t.at());
		}

		Long good = t.goodCount() == null ? null : Counters.delta(machine.lastGoodRaw(), machine.counterBits(), t.goodCount(), t.counterBits());
		Long reject = t.rejectCount() == null ? null
				: Counters.delta(machine.lastRejectRaw(), machine.counterBits(), t.rejectCount(), t.counterBits());
		if ((good != null && good > 0) || (reject != null && reject > 0)) {
			jdbc.sql("INSERT INTO production (machine_id, recorded_at, good, reject) VALUES (:m, :at, :good, :reject)")
				.param("m", machine.id())
				.param("at", Timestamp.from(t.at()))
				.param("good", good == null ? 0 : good)
				.param("reject", reject == null ? 0 : reject)
				.update();
		}
		jdbc.sql("""
				UPDATE machine SET last_good_raw = coalesce(:good, last_good_raw), last_reject_raw = coalesce(:reject, last_reject_raw),
				counter_bits = :bits WHERE id = :m""")
			.param("good", t.goodCount())
			.param("reject", t.rejectCount())
			.param("bits", t.counterBits())
			.param("m", machine.id())
			.update();

		if (t.analogs() != null) {
			t.analogs().forEach((name, a) -> jdbc.sql("""
					INSERT INTO analog_sample (machine_id, name, recorded_at, avg, min, max) VALUES (:m, :name, :at, :avg, :min, :max)""")
				.param("m", machine.id())
				.param("name", name)
				.param("at", Timestamp.from(t.at()))
				.param("avg", a.avg())
				.param("min", a.min())
				.param("max", a.max())
				.update());
		}
	}

	/**
	 * If nothing was heard from the machine for longer than it may stay quiet, the open interval ends at the last
	 * message and the silence becomes NO_DATA. Returns the interval still open afterwards.
	 */
	private Optional<OpenInterval> closeGap(MachineRow machine, Instant at) {
		Optional<OpenInterval> open = openInterval(machine.id());
		Instant lastSeen = machine.lastSeenAt();
		if (open.isEmpty() || lastSeen == null || open.get().state().equals("NO_DATA")) {
			return open;
		}
		if (Duration.between(lastSeen, at).toSeconds() <= machine.staleAfterSeconds()) {
			return open;
		}
		Instant gapStart = lastSeen.isAfter(open.get().startedAt()) ? lastSeen : open.get().startedAt();
		close(open.get(), gapStart);
		long id = openInterval(machine.id(), "NO_DATA", 0, gapStart);
		return Optional.of(new OpenInterval(id, "NO_DATA", 0, gapStart));
	}

	private Optional<MachineRow> lockMachine(String id, String site) {
		Optional<MachineRow> row = jdbc.sql("""
				SELECT id, site, last_seen_at, last_good_raw, last_reject_raw, counter_bits, stale_after_seconds, lorawan_analogs
				FROM machine WHERE id = :id FOR UPDATE""")
			.param("id", id)
			.query((rs, i) -> new MachineRow(rs.getString(1), rs.getString(2), instant(rs.getTimestamp(3)),
					(Long) rs.getObject(4), (Long) rs.getObject(5), (Integer) rs.getObject(6), rs.getInt(7),
					List.of((String[]) rs.getArray(8).getArray())))
			.optional();
		if (row.isPresent() && site != null && !row.get().site().equals(site)) {
			log.warn("machine {} belongs to site {}, not {}", id, row.get().site(), site);
			return Optional.empty();
		}
		return row;
	}

	private boolean firstDelivery(String source, String key) {
		return jdbc.sql("INSERT INTO received_message (source, message_key) VALUES (:s, :k) ON CONFLICT DO NOTHING")
			.param("s", source)
			.param("k", key)
			.update() == 1;
	}

	private static boolean isLate(MachineRow machine, Instant at) {
		return machine.lastSeenAt() != null && at.isBefore(machine.lastSeenAt());
	}

	private Optional<OpenInterval> openInterval(String machineId) {
		return jdbc.sql("SELECT id, state, fault_code, started_at FROM state_interval WHERE machine_id = :m AND ended_at IS NULL")
			.param("m", machineId)
			.query((rs, i) -> new OpenInterval(rs.getLong(1), rs.getString(2), rs.getInt(3), instant(rs.getTimestamp(4))))
			.optional();
	}

	private long openInterval(String machineId, String state, int faultCode, Instant at) {
		return jdbc.sql("""
				INSERT INTO state_interval (machine_id, state, fault_code, started_at) VALUES (:m, :s, :c, :at) RETURNING id""")
			.param("m", machineId)
			.param("s", state)
			.param("c", faultCode)
			.param("at", Timestamp.from(at))
			.query(Long.class)
			.single();
	}

	private void close(OpenInterval open, Instant at) {
		Instant end = at.isBefore(open.startedAt()) ? open.startedAt() : at;
		jdbc.sql("UPDATE state_interval SET ended_at = :at WHERE id = :id").param("at", Timestamp.from(end)).param("id", open.id()).update();
	}

	private void touch(String machineId, Instant at) {
		jdbc.sql("UPDATE machine SET last_seen_at = greatest(coalesce(last_seen_at, :at), :at) WHERE id = :m")
			.param("at", Timestamp.from(at))
			.param("m", machineId)
			.update();
	}

	/** Deduplication only has to outlive redeliveries, which happen within hours, not weeks. */
	@Scheduled(cron = "0 17 3 * * *")
	public void forgetOldMessageKeys() {
		int removed = jdbc.sql("DELETE FROM received_message WHERE received_at < now() - interval '14 days'").update();
		log.info("forgot {} message keys older than 14 days", removed);
	}

	private static Instant instant(Timestamp ts) {
		return ts == null ? null : ts.toInstant();
	}
}
