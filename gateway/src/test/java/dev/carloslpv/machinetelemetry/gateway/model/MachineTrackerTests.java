package dev.carloslpv.machinetelemetry.gateway.model;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import dev.carloslpv.machinetelemetry.gateway.config.GatewayConfig;
import dev.carloslpv.machinetelemetry.gateway.config.GatewayConfig.Scale;
import dev.carloslpv.machinetelemetry.gateway.config.GatewayConfig.Tag;
import dev.carloslpv.machinetelemetry.gateway.model.MachineEvent.StateChange;
import dev.carloslpv.machinetelemetry.gateway.model.MachineEvent.Telemetry;

import static org.assertj.core.api.Assertions.assertThat;

class MachineTrackerTests {

	static final Instant T0 = Instant.parse("2026-10-31T10:00:00Z");

	static GatewayConfig.Machine machine() {
		var signals = new GatewayConfig.Signals(new Tag("r", null, null), new Tag("f", null, null), new Tag("c", null, null),
				new Tag("g", "DINT", null), new Tag("j", "DINT", null));
		return new GatewayConfig.Machine("ENS-01", new GatewayConfig.Source("s7", null, null, null, null, "plc", null, null, null),
				Duration.ofMillis(250), Duration.ofSeconds(10), signals,
				Map.of("motor-temp", new Tag("t", "INT", new Scale(0, 27648, 0, 150))));
	}

	static Map<String, Object> reading(boolean running, boolean fault, int code, long good, long temp) {
		Map<String, Object> values = new HashMap<>();
		values.put(MachineTracker.RUNNING, running);
		values.put(MachineTracker.FAULT, fault);
		values.put(MachineTracker.FAULT_CODE, (long) code);
		values.put(MachineTracker.GOOD_COUNT, good);
		values.put(MachineTracker.REJECT_COUNT, 0L);
		values.put("analog:motor-temp", temp);
		return values;
	}

	@Test
	void theFirstReadingReportsTheState() {
		MachineTracker tracker = new MachineTracker(machine());
		List<MachineEvent> events = tracker.accept(T0, reading(true, false, 0, 10, 0));
		assertThat(events).containsExactly(new StateChange("ENS-01", T0, MachineState.RUNNING, 0, null, 0));
	}

	@Test
	void reportsChangesOnlyWhenTheyHappen() {
		MachineTracker tracker = new MachineTracker(machine());
		tracker.accept(T0, reading(true, false, 0, 10, 0));
		assertThat(tracker.accept(T0.plusMillis(250), reading(true, false, 0, 11, 0))).isEmpty();
		assertThat(tracker.accept(T0.plusMillis(500), reading(false, false, 0, 11, 0)))
			.containsExactly(new StateChange("ENS-01", T0.plusMillis(500), MachineState.IDLE, 0, MachineState.RUNNING, 0));
	}

	@Test
	void faultWinsOverRunningAndANewFaultCodeIsANewStop() {
		MachineTracker tracker = new MachineTracker(machine());
		tracker.accept(T0, reading(true, false, 0, 10, 0));
		assertThat(tracker.accept(T0.plusSeconds(1), reading(true, true, 102, 10, 0)))
			.containsExactly(new StateChange("ENS-01", T0.plusSeconds(1), MachineState.FAULT, 102, MachineState.RUNNING, 0));
		assertThat(tracker.accept(T0.plusSeconds(2), reading(false, true, 101, 10, 0)))
			.containsExactly(new StateChange("ENS-01", T0.plusSeconds(2), MachineState.FAULT, 101, MachineState.FAULT, 102));
	}

	@Test
	void aFaultCodeLeftInThePlcWithoutTheFaultBitIsIgnored() {
		MachineTracker tracker = new MachineTracker(machine());
		List<MachineEvent> events = tracker.accept(T0, reading(true, false, 102, 10, 0));
		assertThat(((StateChange) events.getFirst()).faultCode()).isZero();
	}

	@Test
	void telemetrySummarizesTheWindowWithScaledAnalogs() {
		MachineTracker tracker = new MachineTracker(machine());
		tracker.accept(T0, reading(true, false, 0, 100, 0)); // 0 °C
		tracker.accept(T0.plusSeconds(5), reading(true, false, 0, 101, 27648)); // 150 °C
		List<MachineEvent> events = tracker.accept(T0.plusSeconds(10), reading(true, false, 0, 102, 13824)); // 75 °C
		Telemetry t = (Telemetry) events.getFirst();
		assertThat(t.window()).isEqualTo(Duration.ofSeconds(10));
		assertThat(t.goodCount()).isEqualTo(102);
		assertThat(t.counterBits()).isEqualTo(32);
		assertThat(t.analogs().get("motor-temp")).isEqualTo(new MachineEvent.AnalogStats(75.0, 0.0, 150.0, 75.0, 3));
		// the next window starts empty
		Telemetry next = (Telemetry) tracker.accept(T0.plusSeconds(20), reading(true, false, 0, 103, 0)).getFirst();
		assertThat(next.analogs().get("motor-temp").samples()).isEqualTo(1);
	}

	@Test
	void countersAreSentUnsignedSoAWrappedDintStaysIncreasing() {
		MachineTracker tracker = new MachineTracker(machine());
		tracker.accept(T0, reading(true, false, 0, -1, 0));
		Telemetry t = (Telemetry) tracker.accept(T0.plusSeconds(10), reading(true, false, 0, -2147483648L, 0)).getFirst();
		assertThat(t.goodCount()).isEqualTo(2147483648L);
	}

	@Test
	void losingThePlcIsAStateOfItsOwn() {
		MachineTracker tracker = new MachineTracker(machine());
		tracker.accept(T0, reading(true, false, 0, 10, 0));
		assertThat(tracker.unreachable(T0.plusSeconds(3)))
			.containsExactly(new StateChange("ENS-01", T0.plusSeconds(3), MachineState.NO_DATA, 0, MachineState.RUNNING, 0));
		assertThat(tracker.unreachable(T0.plusSeconds(4))).isEmpty();
		assertThat(tracker.accept(T0.plusSeconds(9), reading(true, false, 0, 10, 0)))
			.containsExactly(new StateChange("ENS-01", T0.plusSeconds(9), MachineState.RUNNING, 0, MachineState.NO_DATA, 0));
	}
}
