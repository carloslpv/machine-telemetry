package dev.carloslpv.machinetelemetry.gateway.uplink;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import io.moquette.broker.Server;
import io.moquette.broker.config.MemoryConfig;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;

import dev.carloslpv.machinetelemetry.gateway.config.GatewayConfig;
import dev.carloslpv.machinetelemetry.gateway.model.MachineEvent;
import dev.carloslpv.machinetelemetry.gateway.model.MachineEvent.AnalogStats;
import dev.carloslpv.machinetelemetry.gateway.model.MachineState;
import dev.carloslpv.machinetelemetry.gateway.model.MachineTrackerTestsSupport;
import dev.carloslpv.machinetelemetry.gateway.outbox.Outbox;

import static org.assertj.core.api.Assertions.assertThat;

class MqttUplinkTests {

	private static final Instant T0 = Instant.parse("2026-10-31T10:00:00Z");

	@TempDir
	Path dir;

	private final int port = freePort();

	private Server broker;

	private MqttUplink uplink;

	private MqttClient listener;

	private final BlockingQueue<String[]> received = new LinkedBlockingQueue<>();

	@AfterEach
	void tearDown() throws Exception {
		if (uplink != null) {
			uplink.close();
		}
		if (listener != null && listener.isConnected()) {
			listener.disconnect();
		}
		if (broker != null) {
			broker.stopServer();
		}
	}

	@Test
	void publishesEventsWithTheirSequenceNumberAndAnnouncesTheGateway() throws Exception {
		startBroker();
		listen();
		start();

		uplink.accept(new MachineEvent.StateChange("ENS-01", T0, MachineState.FAULT, 102, MachineState.RUNNING, 0));
		uplink.accept(new MachineEvent.Telemetry("ENS-01", T0.plusSeconds(10), MachineState.FAULT, 102, Duration.ofSeconds(10),
				1234L, 5L, 32, Map.of("motor-temp", new AnalogStats(70.5, 70.0, 71.0, 70.8, 40))));

		String[] status = next();
		assertThat(status[0]).isEqualTo("mt/joacaba/gateway/gw-test/status");
		assertThat(json(status).get("status").asString()).isEqualTo("online");

		String[] state = next();
		assertThat(state[0]).isEqualTo("mt/joacaba/ENS-01/state");
		JsonNode s = json(state);
		assertThat(s.get("seq").asLong()).isEqualTo(1);
		assertThat(s.get("gatewayId").asString()).isEqualTo("gw-test");
		assertThat(s.get("state").asString()).isEqualTo("FAULT");
		assertThat(s.get("faultCode").asInt()).isEqualTo(102);
		assertThat(s.get("previousState").asString()).isEqualTo("RUNNING");
		assertThat(s.get("at").asString()).isEqualTo("2026-10-31T10:00:00Z");

		String[] telemetry = next();
		assertThat(telemetry[0]).isEqualTo("mt/joacaba/ENS-01/telemetry");
		JsonNode t = json(telemetry);
		assertThat(t.get("seq").asLong()).isEqualTo(2);
		assertThat(t.get("goodCount").asLong()).isEqualTo(1234);
		assertThat(t.get("counterBits").asInt()).isEqualTo(32);
		assertThat(t.get("windowSeconds").asDouble()).isEqualTo(10.0);
		assertThat(t.at("/analogs/motor-temp/avg").asDouble()).isEqualTo(70.5);
	}

	@Test
	void keepsEventsWhileTheBrokerIsDownAndSendsThemInOrderWhenItReturns() throws Exception {
		start(); // no broker yet: the gateway starts anyway and queues
		for (int i = 0; i < 5; i++) {
			uplink.accept(new MachineEvent.StateChange("ENS-01", T0.plusSeconds(i), i % 2 == 0 ? MachineState.RUNNING : MachineState.IDLE, 0,
					null, 0));
		}
		Thread.sleep(1_500);

		startBroker();
		listen();
		List<Long> seqs = new java.util.ArrayList<>();
		while (seqs.size() < 5) {
			String[] message = received.poll(40, TimeUnit.SECONDS);
			assertThat(message).as("message %d after the broker came back", seqs.size() + 1).isNotNull();
			if (message[0].endsWith("/state")) {
				seqs.add(json(message).get("seq").asLong());
			}
		}
		assertThat(seqs).containsExactly(1L, 2L, 3L, 4L, 5L);
	}

	private void start() throws Exception {
		GatewayConfig config = MachineTrackerTestsSupport.gateway("tcp://127.0.0.1:" + port);
		uplink = new MqttUplink(config, new Outbox(dir.resolve("outbox.db"), 1000), Clock.systemUTC());
		uplink.start();
	}

	private void listen() throws Exception {
		listener = new MqttClient("tcp://127.0.0.1:" + port, "listener", new MemoryPersistence());
		listener.setCallback(new org.eclipse.paho.client.mqttv3.MqttCallback() {
			@Override
			public void connectionLost(Throwable cause) {
			}

			@Override
			public void messageArrived(String topic, org.eclipse.paho.client.mqttv3.MqttMessage message) {
				received.add(new String[] { topic, new String(message.getPayload(), StandardCharsets.UTF_8) });
			}

			@Override
			public void deliveryComplete(org.eclipse.paho.client.mqttv3.IMqttDeliveryToken token) {
			}
		});
		MqttConnectOptions options = new MqttConnectOptions();
		options.setCleanSession(true);
		listener.connect(options);
		listener.subscribe("mt/#", 1);
	}

	private String[] next() throws InterruptedException {
		String[] message = received.poll(20, TimeUnit.SECONDS);
		assertThat(message).as("an MQTT message").isNotNull();
		return message;
	}

	private static JsonNode json(String[] message) {
		return Messages.JSON.readTree(message[1]);
	}

	private void startBroker() throws IOException {
		Properties config = new Properties();
		config.setProperty("host", "127.0.0.1");
		config.setProperty("port", String.valueOf(port));
		config.setProperty("allow_anonymous", "true");
		config.setProperty("persistence_enabled", "false");
		config.setProperty("data_path", Files.createTempDirectory(dir, "moquette").toString());
		broker = new Server();
		broker.startServer(new MemoryConfig(config));
	}

	private static int freePort() {
		try (ServerSocket socket = new ServerSocket(0)) {
			return socket.getLocalPort();
		}
		catch (IOException ex) {
			throw new IllegalStateException(ex);
		}
	}
}
