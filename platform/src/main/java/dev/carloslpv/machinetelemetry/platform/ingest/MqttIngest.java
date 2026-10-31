package dev.carloslpv.machinetelemetry.platform.ingest;

import java.time.Duration;

import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import dev.carloslpv.machinetelemetry.platform.PlatformProperties;
import dev.carloslpv.machinetelemetry.platform.ingest.IngestService.Result;
import dev.carloslpv.machinetelemetry.platform.ingest.IngestService.StateEvent;
import dev.carloslpv.machinetelemetry.platform.ingest.IngestService.TelemetryEvent;
import dev.carloslpv.machinetelemetry.platform.ingest.Messages.GatewayStatus;
import dev.carloslpv.machinetelemetry.platform.ingest.Messages.StateMessage;
import dev.carloslpv.machinetelemetry.platform.ingest.Messages.TelemetryMessage;

/**
 * Subscribes to the gateways' topics and applies each message.
 * <p>
 * The session is persistent and acknowledgements are manual: a message is acknowledged only after its transaction
 * committed. While the platform is down, the broker keeps the messages; if the database is down, the callback keeps
 * retrying the same message, which also stops the broker from sending more (back pressure) until it is back.
 */
@Component
@ConditionalOnProperty(name = "platform.mqtt.enabled", havingValue = "true", matchIfMissing = true)
class MqttIngest implements SmartLifecycle, MqttCallbackExtended {

	private static final Logger log = LoggerFactory.getLogger(MqttIngest.class);

	private static final int QOS = 1;

	private final PlatformProperties.Mqtt properties;

	private final IngestService ingest;

	private final IngestMetrics metrics;

	private final JsonMapper json;

	private volatile MqttClient client;

	private volatile boolean running;

	private Thread connector;

	MqttIngest(PlatformProperties properties, IngestService ingest, IngestMetrics metrics, JsonMapper json) {
		this.properties = properties.mqtt();
		this.ingest = ingest;
		this.metrics = metrics;
		this.json = json;
	}

	@Override
	public void start() {
		try {
			client = new MqttClient(properties.uri(), properties.clientId(), new MemoryPersistence());
		}
		catch (MqttException ex) {
			throw new IllegalStateException("invalid MQTT settings: " + ex.getMessage(), ex);
		}
		client.setCallback(this);
		client.setManualAcks(true);
		running = true;
		connector = Thread.ofVirtual().name("mqtt-connect").start(this::connectUntilStopped);
	}

	private void connectUntilStopped() {
		MqttConnectOptions options = new MqttConnectOptions();
		options.setCleanSession(false);
		options.setAutomaticReconnect(true);
		options.setKeepAliveInterval(30);
		options.setMaxInflight(100);
		if (StringUtils.hasText(properties.username())) {
			options.setUserName(properties.username());
			options.setPassword(properties.password() == null ? new char[0] : properties.password().toCharArray());
		}
		Duration delay = Duration.ofSeconds(1);
		while (running) {
			try {
				client.connect(options);
				return;
			}
			catch (MqttException ex) {
				log.warn("cannot connect to {} ({}); retrying in {} s", properties.uri(), ex.getMessage(), delay.toSeconds());
				try {
					Thread.sleep(delay);
				}
				catch (InterruptedException interrupted) {
					return;
				}
				delay = delay.multipliedBy(2).compareTo(Duration.ofSeconds(30)) > 0 ? Duration.ofSeconds(30) : delay.multipliedBy(2);
			}
		}
	}

	@Override
	public void connectComplete(boolean reconnect, String serverUri) {
		log.info("{} to {}", reconnect ? "reconnected" : "connected", serverUri);
		String p = properties.topicPrefix();
		String[] filters = { p + "/+/+/state", p + "/+/+/telemetry", p + "/+/gateway/+/status" };
		// subscribing waits for the callback thread, so it cannot run on it
		Thread.ofVirtual().name("mqtt-subscribe").start(() -> {
			try {
				client.subscribe(filters, new int[] { QOS, QOS, QOS });
			}
			catch (MqttException ex) {
				log.error("subscription failed: {}", ex.getMessage());
			}
		});
	}

	@Override
	public void messageArrived(String topic, MqttMessage message) throws MqttException {
		String[] parts = topic.split("/");
		String kind = parts[parts.length - 1];
		Duration delay = Duration.ofMillis(500);
		while (running) {
			try {
				Result result = handle(parts, kind, message.getPayload());
				metrics.count(kind, result);
				if (result == Result.REJECTED) {
					log.warn("rejected {} on {}: unknown machine or wrong site", kind, topic);
				}
				break;
			}
			catch (JacksonException | IllegalArgumentException | NullPointerException ex) {
				// a message that cannot be read now will not be readable later: count it and move on
				metrics.count(kind, Result.REJECTED);
				log.warn("unreadable {} on {}: {}", kind, topic, ex.getMessage());
				break;
			}
			catch (DataAccessException ex) {
				log.warn("database unavailable ({}); retrying {} in {} ms", ex.getMostSpecificCause().getMessage(), kind, delay.toMillis());
				try {
					Thread.sleep(delay);
				}
				catch (InterruptedException interrupted) {
					Thread.currentThread().interrupt();
					return; // not acknowledged: the broker sends it again
				}
				delay = delay.multipliedBy(2).compareTo(Duration.ofSeconds(30)) > 0 ? Duration.ofSeconds(30) : delay.multipliedBy(2);
			}
		}
		if (running) {
			client.messageArrivedComplete(message.getId(), message.getQos());
		}
	}

	private Result handle(String[] parts, String kind, byte[] payload) {
		// <prefix>/<site>/<machine>/state|telemetry and <prefix>/<site>/gateway/<id>/status
		String site = parts[1];
		switch (kind) {
			case "state" -> {
				StateMessage m = json.readValue(payload, StateMessage.class);
				requireSameMachine(parts[2], m.machineId());
				return ingest.applyState(m.gatewayId(), Long.toString(m.seq()), site,
						new StateEvent(m.machineId(), m.at(), m.state(), m.faultCode()));
			}
			case "telemetry" -> {
				TelemetryMessage m = json.readValue(payload, TelemetryMessage.class);
				requireSameMachine(parts[2], m.machineId());
				return ingest.applyTelemetry(m.gatewayId(), Long.toString(m.seq()), site, new TelemetryEvent(m.machineId(), m.at(),
						m.state(), m.faultCode(), m.goodCount(), m.rejectCount(), m.counterBits(), m.analogs()));
			}
			case "status" -> {
				ingest.gatewayStatus(site, json.readValue(payload, GatewayStatus.class));
				return Result.APPLIED;
			}
			default -> throw new IllegalArgumentException("unexpected topic kind " + kind);
		}
	}

	private static void requireSameMachine(String topicMachine, String payloadMachine) {
		if (!topicMachine.equals(payloadMachine)) {
			throw new IllegalArgumentException("topic is for " + topicMachine + " but the payload is for " + payloadMachine);
		}
	}

	@Override
	public void connectionLost(Throwable cause) {
		log.warn("MQTT connection lost: {}", cause.getMessage());
	}

	@Override
	public void deliveryComplete(IMqttDeliveryToken token) {
		// the platform does not publish
	}

	@Override
	public void stop() {
		running = false;
		if (connector != null) {
			connector.interrupt();
		}
		try {
			if (client.isConnected()) {
				client.disconnect(5_000);
			}
			client.close();
		}
		catch (MqttException ex) {
			log.warn("closing MQTT: {}", ex.getMessage());
		}
	}

	@Override
	public boolean isRunning() {
		return running;
	}

	boolean isConnected() {
		return client != null && client.isConnected();
	}
}
