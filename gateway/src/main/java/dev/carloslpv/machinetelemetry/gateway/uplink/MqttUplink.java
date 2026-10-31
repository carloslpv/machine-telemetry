package dev.carloslpv.machinetelemetry.gateway.uplink;

import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.util.List;

import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import dev.carloslpv.machinetelemetry.gateway.config.GatewayConfig;
import dev.carloslpv.machinetelemetry.gateway.model.MachineEvent;
import dev.carloslpv.machinetelemetry.gateway.outbox.Outbox;

/**
 * Sends events over MQTT, store and forward.
 * <p>
 * Every event goes into the {@link Outbox} first; a single publisher thread sends the oldest one, waits for the
 * broker's PUBACK (QoS 1) and only then deletes it. If the network is down for hours, events pile up on disk and go
 * out in order when it comes back. A crash between the PUBACK and the delete sends the message again, which the
 * platform drops by its {@code gatewayId + seq}. So delivery is at least once on the wire and exactly once in the
 * database.
 * <p>
 * The connection carries a last will: if the gateway disappears without saying goodbye, the broker publishes
 * {@code offline} on the gateway's status topic (retained), so the platform can tell "machine stopped" from
 * "gateway gone".
 */
public final class MqttUplink implements Uplink {

	private static final Logger log = LoggerFactory.getLogger(MqttUplink.class);

	private static final int QOS = 1;

	private static final int BATCH = 200;

	private static final Duration MAX_BACKOFF = Duration.ofSeconds(30);

	private final GatewayConfig config;

	private final GatewayConfig.Mqtt mqtt;

	private final Outbox outbox;

	private final Clock clock;

	private volatile boolean running;

	private Thread publisher;

	private MqttClient client;

	public MqttUplink(GatewayConfig config, Outbox outbox, Clock clock) {
		this.config = config;
		this.mqtt = config.uplink().mqtt();
		this.outbox = outbox;
		this.clock = clock;
	}

	@Override
	public void accept(MachineEvent event) {
		Outbox.Kind kind = event instanceof MachineEvent.StateChange ? Outbox.Kind.STATE : Outbox.Kind.TELEMETRY;
		try {
			outbox.add(kind, Messages.topic(mqtt.topicPrefix(), config.site(), event), Messages.toJson(config.id(), event));
		}
		catch (SQLException ex) {
			log.error("could not store {} of {} in the outbox: {}", kind, event.machineId(), ex.getMessage());
		}
	}

	@Override
	public void start() {
		try {
			client = new MqttClient(mqtt.uri(), config.id(), new MemoryPersistence());
		}
		catch (MqttException ex) {
			throw new IllegalStateException("invalid MQTT settings: " + ex.getMessage(), ex);
		}
		client.setTimeToWait(10_000);
		running = true;
		publisher = Thread.ofVirtual().name("mqtt-publisher").start(this::publishLoop);
	}

	private void publishLoop() {
		Duration backoff = Duration.ofSeconds(1);
		while (running) {
			try {
				if (!client.isConnected()) {
					connect();
					backoff = Duration.ofSeconds(1);
				}
				List<Outbox.Entry> batch = outbox.peek(BATCH);
				if (batch.isEmpty()) {
					outbox.awaitMessages(1_000);
					continue;
				}
				for (Outbox.Entry entry : batch) {
					client.publish(entry.topic(), Messages.withSeq(entry.payload(), entry.seq()), QOS, false);
					outbox.remove(entry.seq());
				}
			}
			catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
				return;
			}
			catch (MqttException | SQLException | RuntimeException ex) {
				if (!running) {
					return;
				}
				log.warn("MQTT {}: {} ({} messages waiting)", mqtt.uri(), ex.getMessage(), outbox.size());
				disconnectQuietly();
				try {
					Thread.sleep(backoff);
				}
				catch (InterruptedException ie) {
					Thread.currentThread().interrupt();
					return;
				}
				backoff = backoff.multipliedBy(2).compareTo(MAX_BACKOFF) > 0 ? MAX_BACKOFF : backoff.multipliedBy(2);
			}
		}
	}

	private void connect() throws MqttException {
		String statusTopic = Messages.statusTopic(mqtt.topicPrefix(), config.site(), config.id());
		MqttConnectOptions options = new MqttConnectOptions();
		// durability comes from the outbox, not from the broker session
		options.setCleanSession(true);
		options.setAutomaticReconnect(false);
		options.setConnectionTimeout(10);
		options.setKeepAliveInterval(30);
		if (mqtt.username() != null && !mqtt.username().isBlank()) {
			options.setUserName(mqtt.username());
			options.setPassword(mqtt.password() == null ? new char[0] : mqtt.password().toCharArray());
		}
		options.setWill(statusTopic, Messages.status(config.id(), "offline", clock.instant()).getBytes(StandardCharsets.UTF_8), QOS, true);
		client.connect(options);
		client.publish(statusTopic, Messages.status(config.id(), "online", clock.instant()).getBytes(StandardCharsets.UTF_8), QOS, true);
		log.info("connected to {} ({} messages waiting, {} dropped so far)", mqtt.uri(), outbox.size(), outbox.dropped());
	}

	private void disconnectQuietly() {
		try {
			if (client.isConnected()) {
				client.disconnectForcibly(1_000, 1_000);
			}
		}
		catch (MqttException ignored) {
			// the next connect starts over anyway
		}
	}

	@Override
	public void close() {
		running = false;
		if (publisher != null) {
			publisher.interrupt();
			try {
				publisher.join(Duration.ofSeconds(5));
			}
			catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
			}
		}
		if (client != null) {
			try {
				if (client.isConnected()) {
					String statusTopic = Messages.statusTopic(mqtt.topicPrefix(), config.site(), config.id());
					client.publish(statusTopic, Messages.status(config.id(), "offline", clock.instant()).getBytes(StandardCharsets.UTF_8), QOS, true);
					client.disconnect(2_000);
				}
				client.close();
			}
			catch (MqttException ex) {
				log.debug("closing MQTT: {}", ex.getMessage());
			}
		}
	}
}
