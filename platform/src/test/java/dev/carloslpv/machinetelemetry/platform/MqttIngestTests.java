package dev.carloslpv.machinetelemetry.platform;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.util.Properties;

import io.moquette.broker.Server;
import io.moquette.broker.config.MemoryConfig;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/** The gateway's messages, as it publishes them, through a real MQTT broker into the database. */
@SpringBootTest
@ActiveProfiles({ "test", "demo" })
@Import(TestDatabase.class)
class MqttIngestTests {

	private static final int PORT = freePort();

	private static final Server broker = startBroker();

	@DynamicPropertySource
	static void mqtt(DynamicPropertyRegistry registry) {
		registry.add("platform.mqtt.enabled", () -> "true");
		registry.add("platform.mqtt.uri", () -> "tcp://127.0.0.1:" + PORT);
		registry.add("platform.mqtt.client-id", () -> "platform-test");
	}

	@AfterAll
	static void stopBroker() {
		broker.stopServer();
	}

	@Autowired
	JdbcClient jdbc;

	@Test
	void appliesGatewayMessagesOnceEach() throws Exception {
		MqttClient gateway = new MqttClient("tcp://127.0.0.1:" + PORT, "gw-joacaba-01", new MemoryPersistence());
		gateway.connect();
		await().atMost(Duration.ofSeconds(20)).until(() -> broker.listConnectedClients().size() >= 2);
		Thread.sleep(500); // the platform subscribes right after connecting

		publish(gateway, "mt/joacaba/gateway/gw-joacaba-01/status", """
				{"gatewayId":"gw-joacaba-01","status":"online","at":"2026-10-31T10:00:00Z"}""");
		String state = """
				{"seq":1,"gatewayId":"gw-joacaba-01","machineId":"ENS-01","at":"2026-10-31T10:00:00Z","state":"RUNNING",
				 "faultCode":0,"previousState":null,"previousFaultCode":0}""";
		publish(gateway, "mt/joacaba/ENS-01/state", state);
		publish(gateway, "mt/joacaba/ENS-01/state", state); // redelivered
		publish(gateway, "mt/joacaba/ENS-01/telemetry", """
				{"seq":2,"gatewayId":"gw-joacaba-01","machineId":"ENS-01","at":"2026-10-31T10:00:10Z","state":"RUNNING","faultCode":0,
				 "windowSeconds":10.0,"goodCount":100,"rejectCount":2,"counterBits":32,
				 "analogs":{"motor-temp":{"avg":68.2,"min":68.0,"max":68.5,"last":68.4,"samples":40}}}""");
		publish(gateway, "mt/joacaba/ENS-01/telemetry", """
				{"seq":3,"gatewayId":"gw-joacaba-01","machineId":"ENS-01","at":"2026-10-31T10:00:20Z","state":"RUNNING","faultCode":0,
				 "windowSeconds":10.0,"goodCount":102,"rejectCount":2,"counterBits":32,"analogs":{}}""");
		// garbage and a payload for another machine than the topic say must not stop the stream
		publish(gateway, "mt/joacaba/ENS-01/state", "not json");
		publish(gateway, "mt/joacaba/ENS-01/state", """
				{"seq":4,"gatewayId":"gw-joacaba-01","machineId":"ENS-02","at":"2026-10-31T10:00:21Z","state":"FAULT","faultCode":1}""");
		publish(gateway, "mt/joacaba/ENS-01/state", """
				{"seq":5,"gatewayId":"gw-joacaba-01","machineId":"ENS-01","at":"2026-10-31T10:00:30Z","state":"FAULT","faultCode":102,
				 "previousState":"RUNNING","previousFaultCode":0}""");

		await().atMost(Duration.ofSeconds(20))
			.until(() -> jdbc.sql("SELECT count(*) FROM state_interval WHERE machine_id = 'ENS-01'").query(Integer.class).single() == 2);
		assertThat(jdbc.sql("SELECT state || ':' || fault_code FROM state_interval WHERE machine_id = 'ENS-01' ORDER BY started_at")
			.query(String.class)
			.list()).containsExactly("RUNNING:0", "FAULT:102");
		assertThat(jdbc.sql("SELECT sum(good) FROM production WHERE machine_id = 'ENS-01'").query(Long.class).single()).isEqualTo(2);
		assertThat(jdbc.sql("SELECT count(*) FROM state_interval WHERE machine_id = 'ENS-02'").query(Integer.class).single()).isZero();
		assertThat(jdbc.sql("SELECT status FROM gateway WHERE id = 'gw-joacaba-01'").query(String.class).single()).isEqualTo("online");
		gateway.disconnect();
	}

	private static void publish(MqttClient client, String topic, String payload) throws Exception {
		client.publish(topic, payload.getBytes(StandardCharsets.UTF_8), 1, false);
	}

	private static int freePort() {
		try (ServerSocket socket = new ServerSocket(0)) {
			return socket.getLocalPort();
		}
		catch (IOException ex) {
			throw new IllegalStateException(ex);
		}
	}

	private static Server startBroker() {
		Properties config = new Properties();
		config.setProperty("host", "127.0.0.1");
		config.setProperty("port", String.valueOf(PORT));
		config.setProperty("allow_anonymous", "true");
		config.setProperty("persistence_enabled", "false");
		try {
			config.setProperty("data_path", Files.createTempDirectory("moquette").toString());
			Server server = new Server();
			server.startServer(new MemoryConfig(config));
			return server;
		}
		catch (IOException ex) {
			throw new IllegalStateException(ex);
		}
	}
}
