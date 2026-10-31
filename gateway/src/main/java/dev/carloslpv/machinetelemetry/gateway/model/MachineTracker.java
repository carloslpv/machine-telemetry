package dev.carloslpv.machinetelemetry.gateway.model;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import dev.carloslpv.machinetelemetry.gateway.config.GatewayConfig;
import dev.carloslpv.machinetelemetry.gateway.config.GatewayConfig.Tag;
import dev.carloslpv.machinetelemetry.gateway.model.MachineEvent.AnalogStats;
import dev.carloslpv.machinetelemetry.gateway.model.MachineEvent.StateChange;
import dev.carloslpv.machinetelemetry.gateway.model.MachineEvent.Telemetry;

/**
 * Turns PLC readings into events: a state change as soon as it is seen, and a telemetry message once per window
 * with the counters and the average, minimum and maximum of each analog value.
 * <p>
 * This is the edge processing of the design. Sending every 250 ms reading would cost bandwidth and storage for
 * nothing; sending only averages would hide when the machine stopped. Sending state changes as events and the rest
 * as aggregates keeps both. No I/O here, so it is tested with plain values and a fake clock.
 */
public final class MachineTracker {

	public static final String RUNNING = "running";

	public static final String FAULT = "fault";

	public static final String FAULT_CODE = "fault-code";

	public static final String GOOD_COUNT = "good-count";

	public static final String REJECT_COUNT = "reject-count";

	private static final String ANALOG_PREFIX = "analog:";

	private final GatewayConfig.Machine machine;

	private MachineState state;

	private int faultCode;

	private Instant windowStart;

	private final Map<String, Accumulator> window = new LinkedHashMap<>();

	private Long goodCount;

	private Long rejectCount;

	public MachineTracker(GatewayConfig.Machine machine) {
		this.machine = machine;
	}

	/** Every PLC variable this machine needs, keyed the way {@link #accept} expects the values. */
	public static Map<String, Tag> tags(GatewayConfig.Machine machine) {
		Map<String, Tag> tags = new LinkedHashMap<>();
		GatewayConfig.Signals s = machine.signals();
		tags.put(RUNNING, s.running());
		if (s.fault() != null) {
			tags.put(FAULT, s.fault());
		}
		if (s.faultCode() != null) {
			tags.put(FAULT_CODE, s.faultCode());
		}
		if (s.goodCount() != null) {
			tags.put(GOOD_COUNT, s.goodCount());
		}
		if (s.rejectCount() != null) {
			tags.put(REJECT_COUNT, s.rejectCount());
		}
		machine.analogs().forEach((name, tag) -> tags.put(ANALOG_PREFIX + name, tag));
		return tags;
	}

	public MachineState state() {
		return state;
	}

	public List<MachineEvent> accept(Instant now, Map<String, Object> values) {
		List<MachineEvent> events = new ArrayList<>();
		boolean fault = bool(values.get(FAULT));
		boolean running = bool(values.get(RUNNING));
		MachineState next = fault ? MachineState.FAULT : running ? MachineState.RUNNING : MachineState.IDLE;
		int code = fault && values.get(FAULT_CODE) instanceof Number n ? n.intValue() : 0;
		changeTo(now, next, code, events);

		if (windowStart == null) {
			windowStart = now;
		}
		goodCount = counter(values.get(GOOD_COUNT), GOOD_COUNT);
		rejectCount = counter(values.get(REJECT_COUNT), REJECT_COUNT);
		machine.analogs().forEach((name, tag) -> {
			if (values.get(ANALOG_PREFIX + name) instanceof Number n) {
				window.computeIfAbsent(name, k -> new Accumulator()).add(tag.scaled(n.doubleValue()));
			}
		});
		if (Duration.between(windowStart, now).compareTo(machine.telemetryInterval()) >= 0) {
			events.add(flush(now));
		}
		return events;
	}

	/** The PLC could not be read: the state becomes unknown and the open window is dropped. */
	public List<MachineEvent> unreachable(Instant now) {
		List<MachineEvent> events = new ArrayList<>();
		changeTo(now, MachineState.NO_DATA, 0, events);
		window.clear();
		windowStart = null;
		return events;
	}

	private void changeTo(Instant now, MachineState next, int code, List<MachineEvent> events) {
		if (next != state || code != faultCode) {
			events.add(new StateChange(machine.id(), now, next, code, state, faultCode));
			state = next;
			faultCode = code;
		}
	}

	private Telemetry flush(Instant now) {
		Map<String, AnalogStats> analogs = new LinkedHashMap<>();
		window.forEach((name, acc) -> analogs.put(name, acc.stats()));
		Telemetry telemetry = new Telemetry(machine.id(), now, state, faultCode, Duration.between(windowStart, now),
				goodCount, rejectCount, counterBits(), analogs);
		window.clear();
		windowStart = now;
		return telemetry;
	}

	private int counterBits() {
		Tag tag = machine.signals().goodCount();
		if (tag == null || tag.type() == null) {
			return 32;
		}
		return switch (tag.type()) {
			case "INT", "WORD" -> 16;
			case "LINT", "ULINT" -> 64;
			default -> 32;
		};
	}

	private Long counter(Object value, String key) {
		if (!(value instanceof Number n)) {
			return null;
		}
		int bits = counterBits();
		// a DINT counter read as -1 is 2^32 - 1 counts, not minus one
		return bits == 64 ? n.longValue() : n.longValue() & ((1L << bits) - 1);
	}

	private static boolean bool(Object value) {
		return switch (value) {
			case Boolean b -> b;
			case Number n -> n.longValue() != 0;
			case null, default -> false;
		};
	}

	private static final class Accumulator {

		private double sum;

		private double min = Double.POSITIVE_INFINITY;

		private double max = Double.NEGATIVE_INFINITY;

		private double last;

		private int count;

		void add(double value) {
			sum += value;
			min = Math.min(min, value);
			max = Math.max(max, value);
			last = value;
			count++;
		}

		AnalogStats stats() {
			return new AnalogStats(round(sum / count), round(min), round(max), round(last), count);
		}

		private static double round(double value) {
			return Math.round(value * 1000.0) / 1000.0;
		}
	}
}
