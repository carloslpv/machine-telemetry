package dev.carloslpv.machinetelemetry.gateway.config;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConfigLoaderTests {

	@Test
	void theExampleConfigLoads() throws Exception {
		GatewayConfig config = ConfigLoader.parse(Files.readString(Path.of("gateway.example.yaml")),
				Map.of("PLC2_HOST", "10.0.0.20"));
		assertThat(config.id()).isEqualTo("gw-joacaba-01");
		assertThat(config.machines()).hasSize(2);

		GatewayConfig.Machine opcua = config.machines().get(0);
		assertThat(opcua.source().type()).isEqualTo("opcua");
		assertThat(opcua.pollInterval()).isEqualTo(Duration.ofMillis(250));
		assertThat(opcua.signals().running().address()).isEqualTo("\"DB_Telemetria\".\"Running\"");
		assertThat(opcua.analogs().get("motor-temp").scaled(13824)).isEqualTo(75.0);

		GatewayConfig.Machine s7 = config.machines().get(1);
		assertThat(s7.source().host()).isEqualTo("10.0.0.20");
		assertThat(s7.source().port()).isEqualTo(102);
		assertThat(s7.telemetryInterval()).isEqualTo(Duration.ofSeconds(10));

		assertThat(config.uplink().lorawan().minInterval()).isEqualTo(Duration.ofSeconds(6));
	}

	@Test
	void placeholdersUseTheEnvironmentThenTheDefault() {
		assertThat(ConfigLoader.resolve("a: ${X:1} b: ${Y:2}", Map.of("X", "9"))).isEqualTo("a: 9 b: 2");
		assertThat(ConfigLoader.resolve("p: ${P:}", Map.of())).isEqualTo("p: ");
		assertThatThrownBy(() -> ConfigLoader.resolve("p: ${SECRET}", Map.of())).hasMessageContaining("SECRET");
	}

	@Test
	void durationsAreWrittenTheHumanWay() {
		assertThat(ConfigLoader.parseDuration("250ms")).isEqualTo(Duration.ofMillis(250));
		assertThat(ConfigLoader.parseDuration("5m")).isEqualTo(Duration.ofMinutes(5));
		assertThatThrownBy(() -> ConfigLoader.parseDuration("5 minutes")).isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void aTypoInAKeyIsAnErrorNotASilentDefault() {
		String yaml = """
				id: gw
				site: s
				machines:
				  - id: M1
				    source: { type: s7, host: plc }
				    signals: { running: { address: DB1.DBX0.0 } }
				    pol-interval: 1s
				uplink: { type: mqtt, mqtt: { uri: "tcp://b:1883" } }
				""";
		assertThatThrownBy(() -> ConfigLoader.parse(yaml, Map.of())).hasMessageContaining("pol-interval");
	}

	@Test
	void idsMustBeSafeInTopics() {
		String yaml = """
				id: gw/1
				site: s
				machines:
				  - id: M1
				    source: { type: s7, host: plc }
				    signals: { running: { address: DB1.DBX0.0 } }
				uplink: { type: mqtt, mqtt: { uri: "tcp://b:1883" } }
				""";
		assertThatThrownBy(() -> ConfigLoader.parse(yaml, Map.of())).hasStackTraceContaining("MQTT topics");
	}
}
