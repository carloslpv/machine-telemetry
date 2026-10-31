package dev.carloslpv.machinetelemetry.gateway.config;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Everything the gateway needs, read from one YAML file (see {@link ConfigLoader}).
 * <p>
 * A gateway serves one site, reads one or more machines, and sends what it sees over one uplink.
 */
public record GatewayConfig(String id, String site, String dataDir, List<Machine> machines, Uplink uplink) {

	public GatewayConfig {
		requireText(id, "id");
		requireText(site, "site");
		dataDir = dataDir == null || dataDir.isBlank() ? "data" : dataDir;
		if (machines == null || machines.isEmpty()) {
			throw new IllegalArgumentException("at least one machine must be configured");
		}
		Objects.requireNonNull(uplink, "uplink");
		if (!id.matches("[A-Za-z0-9_-]+") || !site.matches("[A-Za-z0-9_-]+")) {
			throw new IllegalArgumentException("id and site may only use letters, digits, '-' and '_' (they go in MQTT topics)");
		}
	}

	/** One machine and where its PLC data lives. */
	public record Machine(String id, Source source, Duration pollInterval, Duration telemetryInterval,
			Signals signals, Map<String, Tag> analogs) {

		public Machine {
			requireText(id, "machine id");
			if (!id.matches("[A-Za-z0-9_-]+")) {
				throw new IllegalArgumentException("machine id '" + id + "' may only use letters, digits, '-' and '_'");
			}
			Objects.requireNonNull(source, "source of machine " + id);
			Objects.requireNonNull(signals, "signals of machine " + id);
			pollInterval = pollInterval == null ? Duration.ofMillis(250) : pollInterval;
			telemetryInterval = telemetryInterval == null ? Duration.ofSeconds(10) : telemetryInterval;
			analogs = analogs == null ? Map.of() : Map.copyOf(analogs);
		}
	}

	/**
	 * The PLC connection. {@code type} is {@code opcua} (the CPU's OPC UA server) or {@code s7} (S7 PUT/GET).
	 */
	public record Source(String type, String endpoint, String namespaceUri, String username, String password,
			String host, Integer port, Integer rack, Integer slot) {

		public Source {
			requireText(type, "source type");
			switch (type) {
				case "opcua" -> requireText(endpoint, "opcua endpoint");
				case "s7" -> requireText(host, "s7 host");
				default -> throw new IllegalArgumentException("source type must be 'opcua' or 's7', not '" + type + "'");
			}
			port = port == null ? 102 : port;
			rack = rack == null ? 0 : rack;
			slot = slot == null ? 1 : slot;
		}
	}

	/** The PLC variables the machine state comes from. Only {@code running} is mandatory. */
	public record Signals(Tag running, Tag fault, Tag faultCode, Tag goodCount, Tag rejectCount) {

		public Signals {
			Objects.requireNonNull(running, "signals.running");
		}
	}

	/**
	 * One PLC variable. {@code address} is a node id for OPC UA ({@code "DB"."Var"} or a full {@code ns=3;s=...}) or
	 * an S7 address ({@code DB1.DBX0.0}, {@code DB1.DBW2}, {@code DB1.DBD4}). {@code type} is the S7 type (BOOL, INT,
	 * DINT, REAL); it is required for S7 and for counters, where it sets how they wrap.
	 */
	public record Tag(String address, String type, Scale scale) {

		public Tag {
			requireText(address, "tag address");
			type = type == null ? null : type.toUpperCase();
		}

		public double scaled(double raw) {
			return scale == null ? raw : scale.apply(raw);
		}
	}

	/** Linear scaling of a raw value, the SCALE_X of the PLC program done at the edge instead. */
	public record Scale(double rawMin, double rawMax, double min, double max) {

		public Scale {
			if (rawMax == rawMin) {
				throw new IllegalArgumentException("scale raw-min and raw-max must differ");
			}
		}

		double apply(double raw) {
			return min + (raw - rawMin) * (max - min) / (rawMax - rawMin);
		}
	}

	/** Where the data goes. {@code type} is {@code mqtt} or {@code lorawan}. */
	public record Uplink(String type, Mqtt mqtt, LoraWan lorawan) {

		public Uplink {
			requireText(type, "uplink type");
			switch (type) {
				case "mqtt" -> Objects.requireNonNull(mqtt, "uplink.mqtt");
				case "lorawan" -> Objects.requireNonNull(lorawan, "uplink.lorawan");
				default -> throw new IllegalArgumentException("uplink type must be 'mqtt' or 'lorawan', not '" + type + "'");
			}
		}
	}

	public record Mqtt(String uri, String username, String password, String topicPrefix, Integer outboxLimit) {

		public Mqtt {
			requireText(uri, "mqtt uri");
			topicPrefix = topicPrefix == null || topicPrefix.isBlank() ? "mt" : topicPrefix;
			outboxLimit = outboxLimit == null ? 100_000 : outboxLimit;
		}
	}

	/**
	 * A LoRaWAN modem on a serial port, driven with AT commands. The defaults come from the TCC: a module that
	 * answers "busy" when sent to faster than every six seconds.
	 */
	public record LoraWan(String device, Integer baud, Boolean configurePort, String sendTemplate,
			String successPattern, Integer successCount, String busyPattern, String errorPattern,
			Duration minInterval, Duration interval, Duration responseTimeout, List<String> analogs) {

		public LoraWan {
			requireText(device, "lorawan device");
			baud = baud == null ? 9600 : baud;
			configurePort = configurePort == null || configurePort;
			sendTemplate = sendTemplate == null ? "AT+SENDB={port}:{hex}" : sendTemplate;
			successPattern = successPattern == null ? "^OK$" : successPattern;
			successCount = successCount == null ? 1 : successCount;
			busyPattern = busyPattern == null ? "(?i).*busy.*" : busyPattern;
			errorPattern = errorPattern == null ? "(?i).*error.*" : errorPattern;
			minInterval = minInterval == null ? Duration.ofSeconds(6) : minInterval;
			interval = interval == null ? Duration.ofMinutes(5) : interval;
			responseTimeout = responseTimeout == null ? Duration.ofSeconds(15) : responseTimeout;
			analogs = analogs == null ? List.of() : List.copyOf(analogs);
		}
	}

	private static void requireText(String value, String what) {
		if (value == null || value.isBlank()) {
			throw new IllegalArgumentException(what + " is required");
		}
	}
}
