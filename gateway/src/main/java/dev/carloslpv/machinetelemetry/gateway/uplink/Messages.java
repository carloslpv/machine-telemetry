package dev.carloslpv.machinetelemetry.gateway.uplink;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import dev.carloslpv.machinetelemetry.gateway.model.MachineEvent;
import dev.carloslpv.machinetelemetry.gateway.model.MachineEvent.StateChange;
import dev.carloslpv.machinetelemetry.gateway.model.MachineEvent.Telemetry;

/**
 * JSON written to MQTT. The contract with the platform is documented in docs/mqtt.md; field names here and there
 * must match.
 */
final class Messages {

	static final ObjectMapper JSON = JsonMapper.builder().build();

	private Messages() {
	}

	static String topic(String prefix, String site, MachineEvent event) {
		String leaf = event instanceof StateChange ? "state" : "telemetry";
		return prefix + "/" + site + "/" + event.machineId() + "/" + leaf;
	}

	static String statusTopic(String prefix, String site, String gatewayId) {
		return prefix + "/" + site + "/gateway/" + gatewayId + "/status";
	}

	static String toJson(String gatewayId, MachineEvent event) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("gatewayId", gatewayId);
		body.put("machineId", event.machineId());
		body.put("at", event.at().toString());
		switch (event) {
			case StateChange s -> {
				body.put("state", s.state().name());
				body.put("faultCode", s.faultCode());
				body.put("previousState", s.previousState() == null ? null : s.previousState().name());
				body.put("previousFaultCode", s.previousFaultCode());
			}
			case Telemetry t -> {
				body.put("state", t.state().name());
				body.put("faultCode", t.faultCode());
				body.put("windowSeconds", t.window().toMillis() / 1000.0);
				body.put("goodCount", t.goodCount());
				body.put("rejectCount", t.rejectCount());
				body.put("counterBits", t.counterBits());
				body.put("analogs", t.analogs());
			}
		}
		return JSON.writeValueAsString(body);
	}

	static String status(String gatewayId, String status, Instant at) {
		return JSON.writeValueAsString(Map.of("gatewayId", gatewayId, "status", status, "at", at.toString()));
	}

	/** Adds the outbox sequence number, which only exists once the message is stored. */
	static byte[] withSeq(String payload, long seq) {
		ObjectNode node = (ObjectNode) JSON.readTree(payload);
		ObjectNode out = JSON.createObjectNode();
		out.put("seq", seq);
		out.setAll(node);
		return JSON.writeValueAsBytes(out);
	}
}
