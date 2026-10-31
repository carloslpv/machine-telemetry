package dev.carloslpv.machinetelemetry.gateway.model;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

/** What a machine monitor hands to the uplink. */
public sealed interface MachineEvent {

	String machineId();

	Instant at();

	/** The machine changed state, or stayed faulted with a different fault code. */
	record StateChange(String machineId, Instant at, MachineState state, int faultCode, MachineState previousState,
			int previousFaultCode) implements MachineEvent {
	}

	/**
	 * Counters and analog statistics over the last window. Counters are the raw PLC values made unsigned, so the
	 * receiver can compute deltas modulo {@code 2^counterBits} and survive a counter wrapping around.
	 */
	record Telemetry(String machineId, Instant at, MachineState state, int faultCode, Duration window, Long goodCount,
			Long rejectCount, int counterBits, Map<String, AnalogStats> analogs) implements MachineEvent {
	}

	record AnalogStats(double avg, double min, double max, double last, int samples) {
	}
}
