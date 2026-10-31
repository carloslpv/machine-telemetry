package dev.carloslpv.machinetelemetry.platform.lorawan;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import dev.carloslpv.machinetelemetry.platform.PlatformProperties;
import dev.carloslpv.machinetelemetry.platform.ingest.IngestMetrics;
import dev.carloslpv.machinetelemetry.platform.ingest.IngestService;
import dev.carloslpv.machinetelemetry.platform.ingest.IngestService.Result;
import dev.carloslpv.machinetelemetry.platform.ingest.Messages.Analog;

/**
 * Receives uplinks from a The Things Network webhook, where the TCC's architecture sent them to TagoIO.
 * <p>
 * The checks the TCC's TagoIO payload parser did (application and device) are kept: the request must carry the
 * webhook token, come from the configured application, and the device and FPort must belong to a registered machine.
 * TTN retries a webhook that failed, so each uplink is deduplicated by session key and frame counter.
 */
@RestController
class TtnWebhookController {

	private static final Logger log = LoggerFactory.getLogger(TtnWebhookController.class);

	private final PlatformProperties.Ttn ttn;

	private final IngestService ingest;

	private final IngestMetrics metrics;

	TtnWebhookController(PlatformProperties properties, IngestService ingest, IngestMetrics metrics) {
		this.ttn = properties.ttn() == null ? new PlatformProperties.Ttn(null, null) : properties.ttn();
		this.ingest = ingest;
		this.metrics = metrics;
	}

	@PostMapping("/api/v1/integrations/ttn/uplink")
	ResponseEntity<Map<String, Object>> uplink(@RequestHeader(name = "X-Webhook-Token", required = false) String token,
			@RequestBody JsonNode body) {
		if (ttn.webhookToken() == null || ttn.webhookToken().isBlank() || token == null
				|| !MessageDigest.isEqual(token.getBytes(StandardCharsets.UTF_8), ttn.webhookToken().getBytes(StandardCharsets.UTF_8))) {
			return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
		}
		String application = body.at("/end_device_ids/application_ids/application_id").asString("");
		if (ttn.applicationId() != null && !ttn.applicationId().isBlank() && !ttn.applicationId().equals(application)) {
			log.warn("uplink from application '{}' ignored", application);
			return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
		}
		JsonNode up = body.get("uplink_message");
		if (up == null || up.get("frm_payload") == null) {
			// join accepts and other events come through the same webhook when enabled; nothing to store
			return ResponseEntity.ok(Map.of("result", "ignored"));
		}
		String devEui = body.at("/end_device_ids/dev_eui").asString("").toUpperCase();
		int port = up.path("f_port").asInt(0);
		long fCnt = up.path("f_cnt").asLong(0);
		Instant at = Instant.parse(up.path("received_at").asString(body.path("received_at").asString()));
		String key = up.path("session_key_id").asString("") + ":" + fCnt;

		UplinkDecoder.Uplink decoded;
		try {
			decoded = UplinkDecoder.decode(Base64.getDecoder().decode(up.get("frm_payload").asString()));
		}
		catch (IllegalArgumentException ex) {
			metrics.count("lorawan", Result.REJECTED);
			return ResponseEntity.badRequest().body(Map.of("error", ex.getMessage()));
		}

		JsonNode best = null;
		for (JsonNode rx : up.path("rx_metadata")) {
			if (best == null || rx.path("rssi").asInt(-200) > best.path("rssi").asInt(-200)) {
				best = rx;
			}
		}
		Integer rssi = best == null || best.get("rssi") == null ? null : best.get("rssi").asInt();
		Float snr = best == null || best.get("snr") == null ? null : (float) best.get("snr").asDouble();
		String gateway = best == null ? null : best.at("/gateway_ids/gateway_id").asString(null);

		List<String> names = ingest.lorawanAnalogs(devEui, port);
		Map<String, Analog> analogs = new LinkedHashMap<>();
		for (int i = 0; i < Math.min(names.size(), decoded.analogs().size()); i++) {
			Double v = decoded.analogs().get(i);
			if (v != null) {
				analogs.put(names.get(i), new Analog(v, v, v, v, 1));
			}
		}
		Result result = ingest.applyLoraUplink(devEui, port, key, at, decoded.secondsInState(),
				new IngestService.StateEvent(null, at, decoded.state(), decoded.faultCode()),
				new IngestService.TelemetryEvent(null, at, decoded.state(), decoded.faultCode(), (long) decoded.goodCount16(),
						(long) decoded.rejectCount16(), 16, analogs),
				rssi, snr, fCnt, gateway);
		metrics.count("lorawan", result);
		if (result == Result.REJECTED) {
			log.warn("uplink from {} port {} does not belong to a registered machine", devEui, port);
		}
		// TTN only needs a 2xx to stop retrying; an unknown device is not something a retry would fix
		return ResponseEntity.ok(Map.of("result", result.name().toLowerCase()));
	}
}
