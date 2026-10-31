package dev.carloslpv.machinetelemetry.platform.ingest;

import java.time.Instant;
import java.util.Map;

/** What the gateway publishes. The contract is docs/mqtt.md; the gateway writes these fields. */
public final class Messages {

	private Messages() {
	}

	public record StateMessage(long seq, String gatewayId, String machineId, Instant at, String state, int faultCode,
			String previousState, Integer previousFaultCode) {
	}

	public record TelemetryMessage(long seq, String gatewayId, String machineId, Instant at, String state, int faultCode,
			double windowSeconds, Long goodCount, Long rejectCount, int counterBits, Map<String, Analog> analogs) {
	}

	public record Analog(double avg, double min, double max, double last, int samples) {
	}

	public record GatewayStatus(String gatewayId, String status, Instant at) {
	}
}
