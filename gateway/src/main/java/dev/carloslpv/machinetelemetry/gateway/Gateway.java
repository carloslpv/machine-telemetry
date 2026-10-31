package dev.carloslpv.machinetelemetry.gateway;

import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import dev.carloslpv.machinetelemetry.gateway.config.ConfigLoader;
import dev.carloslpv.machinetelemetry.gateway.config.GatewayConfig;
import dev.carloslpv.machinetelemetry.gateway.lorawan.AtModem;
import dev.carloslpv.machinetelemetry.gateway.lorawan.FileSerialLine;
import dev.carloslpv.machinetelemetry.gateway.model.MachineMonitor;
import dev.carloslpv.machinetelemetry.gateway.model.MachineTracker;
import dev.carloslpv.machinetelemetry.gateway.outbox.Outbox;
import dev.carloslpv.machinetelemetry.gateway.source.TagSource;
import dev.carloslpv.machinetelemetry.gateway.uplink.LoraWanUplink;
import dev.carloslpv.machinetelemetry.gateway.uplink.MqttUplink;
import dev.carloslpv.machinetelemetry.gateway.uplink.Uplink;

/**
 * Entry point. {@code java -jar gateway.jar [--check] [config.yaml]}.
 * <p>
 * {@code --check} reads every machine once, prints the values and exits: the commissioning step, to see that the
 * addresses point at the right variables before anything is sent anywhere.
 */
public final class Gateway {

	private static final Logger log = LoggerFactory.getLogger(Gateway.class);

	private Gateway() {
	}

	public static void main(String[] args) throws Exception {
		boolean check = false;
		String file = System.getenv().getOrDefault("GATEWAY_CONFIG", "gateway.yaml");
		for (String arg : args) {
			if (arg.equals("--check")) {
				check = true;
			}
			else {
				file = arg;
			}
		}
		GatewayConfig config = ConfigLoader.load(Path.of(file));
		System.exit(check ? check(config) : run(config));
	}

	static int check(GatewayConfig config) {
		int failures = 0;
		for (GatewayConfig.Machine machine : config.machines()) {
			try (TagSource source = TagSource.create(machine.source(), MachineTracker.tags(machine))) {
				source.connect();
				Map<String, Object> values = source.read();
				System.out.println(machine.id() + " (" + source.describe() + ")");
				values.forEach((key, value) -> System.out.println("  " + key + " = " + value));
			}
			catch (Exception ex) {
				failures++;
				System.out.println(machine.id() + ": FAILED " + ex);
			}
		}
		return failures == 0 ? 0 : 1;
	}

	static int run(GatewayConfig config) throws Exception {
		Clock clock = Clock.systemUTC();
		Uplink uplink = switch (config.uplink().type()) {
			case "mqtt" -> new MqttUplink(config,
					new Outbox(Path.of(config.dataDir(), "outbox.db"), config.uplink().mqtt().outboxLimit()), clock);
			case "lorawan" -> {
				GatewayConfig.LoraWan lora = config.uplink().lorawan();
				yield new LoraWanUplink(config,
						new AtModem(new FileSerialLine(lora.device(), lora.baud(), lora.configurePort()), lora), clock);
			}
			default -> throw new IllegalArgumentException(config.uplink().type());
		};
		uplink.start();

		List<MachineMonitor> monitors = new ArrayList<>();
		List<Thread> threads = new ArrayList<>();
		for (GatewayConfig.Machine machine : config.machines()) {
			Map<String, GatewayConfig.Tag> tags = MachineTracker.tags(machine);
			MachineMonitor monitor = new MachineMonitor(machine, () -> TagSource.create(machine.source(), tags), uplink, clock);
			monitors.add(monitor);
			threads.add(Thread.ofVirtual().name("machine-" + machine.id()).start(monitor));
		}
		log.info("gateway {} at site {}: {} machine(s), uplink {}", config.id(), config.site(), monitors.size(), config.uplink().type());

		CountDownLatch stopped = new CountDownLatch(1);
		Runtime.getRuntime().addShutdownHook(new Thread(() -> {
			log.info("stopping");
			monitors.forEach(MachineMonitor::stop);
			threads.forEach(Thread::interrupt);
			uplink.close();
			stopped.countDown();
		}));
		stopped.await();
		return 0;
	}
}
