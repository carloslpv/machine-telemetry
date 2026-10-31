package dev.carloslpv.machinetelemetry.gateway.uplink;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import dev.carloslpv.machinetelemetry.gateway.config.GatewayConfig;
import dev.carloslpv.machinetelemetry.gateway.lorawan.AtModem;
import dev.carloslpv.machinetelemetry.gateway.lorawan.FakeModem;
import dev.carloslpv.machinetelemetry.gateway.model.MachineEvent;
import dev.carloslpv.machinetelemetry.gateway.model.MachineEvent.AnalogStats;
import dev.carloslpv.machinetelemetry.gateway.model.MachineState;
import dev.carloslpv.machinetelemetry.gateway.model.MachineTrackerTestsSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

class LoraWanUplinkTests {

	private LoraWanUplink uplink;

	@AfterEach
	void stop() {
		if (uplink != null) {
			uplink.close();
		}
	}

	private static GatewayConfig.LoraWan lora(Duration minInterval, Duration interval) {
		return new GatewayConfig.LoraWan("/dev/null", null, false, null, null, null, null, null, minInterval, interval,
				Duration.ofMillis(500), List.of("motor-current", "motor-temp"));
	}

	@Test
	void sendsAStateChangeRightAwayWithTotalsAndAnalogs() {
		FakeModem modem = new FakeModem(cmd -> List.of("OK"));
		GatewayConfig.LoraWan config = lora(Duration.ofMillis(100), Duration.ofMinutes(5));
		uplink = new LoraWanUplink(MachineTrackerTestsSupport.loraGateway(config, 1), new AtModem(modem, config), Clock.systemUTC());
		uplink.start();

		Instant now = Instant.now();
		uplink.accept(new MachineEvent.Telemetry("M1", now, MachineState.RUNNING, 0, Duration.ofSeconds(10), 70000L, 3L, 32,
				Map.of("motor-current", new AnalogStats(7.84, 7.0, 8.0, 7.9, 40), "motor-temp", new AnalogStats(71.3, 71, 72, 71, 40))));
		uplink.accept(new MachineEvent.StateChange("M1", now, MachineState.FAULT, 102, MachineState.RUNNING, 0));

		await().atMost(Duration.ofSeconds(5)).until(() -> !modem.commands.isEmpty());
		// fault 102, 70000 mod 65536 = 4464 (0x1170), 3 rejects, 0 s in state, 7.8 A, 71.3 °C
		assertThat(modem.commands.getFirst()).isEqualTo("AT+SENDB=1:120066117000030000004E02C9");
	}

	@Test
	void neverSendsFasterThanTheModemAllowsAndRetriesWhenBusy() {
		AtomicInteger calls = new AtomicInteger();
		// busy on the first attempt, like the TCC's module when sent to under six seconds apart
		FakeModem modem = new FakeModem(cmd -> List.of(calls.getAndIncrement() == 0 ? "AT_BUSY_ERROR" : "OK"));
		GatewayConfig.LoraWan config = lora(Duration.ofMillis(400), Duration.ofMinutes(5));
		uplink = new LoraWanUplink(MachineTrackerTestsSupport.loraGateway(config, 2), new AtModem(modem, config), Clock.systemUTC());
		uplink.start();

		Instant start = Instant.now();
		uplink.accept(new MachineEvent.StateChange("M1", start, MachineState.RUNNING, 0, null, 0));
		uplink.accept(new MachineEvent.StateChange("M2", start, MachineState.IDLE, 0, null, 0));

		await().atMost(Duration.ofSeconds(10)).until(() -> modem.commands.size() >= 3);
		Duration elapsed = Duration.between(start, Instant.now());
		// busy retry for M1, then M1, then M2: at least two minimum intervals
		assertThat(elapsed).isGreaterThanOrEqualTo(Duration.ofMillis(800));
		assertThat(modem.commands.get(1)).startsWith("AT+SENDB=1:11");
		assertThat(modem.commands.get(2)).startsWith("AT+SENDB=2:10");
	}

	@Test
	void repeatsTheLatestPictureEveryIntervalWithoutAChange() {
		FakeModem modem = new FakeModem(cmd -> List.of("OK"));
		GatewayConfig.LoraWan config = lora(Duration.ofMillis(50), Duration.ofMillis(300));
		uplink = new LoraWanUplink(MachineTrackerTestsSupport.loraGateway(config, 1), new AtModem(modem, config), Clock.systemUTC());
		uplink.start();
		uplink.accept(new MachineEvent.StateChange("M1", Instant.now(), MachineState.RUNNING, 0, null, 0));
		await().atMost(Duration.ofSeconds(5)).until(() -> modem.commands.size() >= 3);
	}
}
