package dev.carloslpv.machinetelemetry.gateway.model;

import java.time.Duration;
import java.util.List;

import dev.carloslpv.machinetelemetry.gateway.config.GatewayConfig;

/** Small configs for the uplink tests. */
public final class MachineTrackerTestsSupport {

	private MachineTrackerTestsSupport() {
	}

	public static GatewayConfig gateway(String mqttUri) {
		return new GatewayConfig("gw-test", "joacaba", null, List.of(MachineTrackerTests.machine()),
				new GatewayConfig.Uplink("mqtt", new GatewayConfig.Mqtt(mqttUri, null, null, null, null), null));
	}

	public static GatewayConfig loraGateway(GatewayConfig.LoraWan lora, int machines) {
		List<GatewayConfig.Machine> list = new java.util.ArrayList<>();
		for (int i = 1; i <= machines; i++) {
			GatewayConfig.Machine m = MachineTrackerTests.machine();
			list.add(new GatewayConfig.Machine("M" + i, m.source(), m.pollInterval(), Duration.ofSeconds(10), m.signals(), m.analogs()));
		}
		return new GatewayConfig("gw-test", "joacaba", null, list, new GatewayConfig.Uplink("lorawan", null, lora));
	}
}
