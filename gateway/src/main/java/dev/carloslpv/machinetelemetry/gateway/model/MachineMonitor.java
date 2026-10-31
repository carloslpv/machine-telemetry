package dev.carloslpv.machinetelemetry.gateway.model;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import dev.carloslpv.machinetelemetry.gateway.config.GatewayConfig;
import dev.carloslpv.machinetelemetry.gateway.source.TagSource;

/**
 * The read loop of one machine: connect, read every poll interval, hand events to the uplink, and reconnect with
 * backoff when the PLC goes away. Runs on its own virtual thread.
 */
public final class MachineMonitor implements Runnable {

	private static final Logger log = LoggerFactory.getLogger(MachineMonitor.class);

	private static final Duration MAX_BACKOFF = Duration.ofSeconds(30);

	private final GatewayConfig.Machine machine;

	private final Supplier<TagSource> sources;

	private final Consumer<MachineEvent> uplink;

	private final Clock clock;

	private final MachineTracker tracker;

	private volatile boolean running = true;

	public MachineMonitor(GatewayConfig.Machine machine, Supplier<TagSource> sources, Consumer<MachineEvent> uplink, Clock clock) {
		this.machine = machine;
		this.sources = sources;
		this.uplink = uplink;
		this.clock = clock;
		this.tracker = new MachineTracker(machine);
	}

	@Override
	public void run() {
		Duration backoff = Duration.ofSeconds(1);
		while (running) {
			TagSource source = sources.get();
			try {
				source.connect();
				log.info("{}: connected to {}", machine.id(), source.describe());
				backoff = Duration.ofSeconds(1);
				pollUntilFailure(source);
			}
			catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
				return;
			}
			catch (Exception ex) {
				if (running) {
					log.warn("{}: {} failed: {}", machine.id(), source.describe(), message(ex));
					publish(tracker.unreachable(clock.instant()));
				}
			}
			finally {
				source.close();
			}
			if (running && !sleep(backoff)) {
				return;
			}
			backoff = backoff.multipliedBy(2).compareTo(MAX_BACKOFF) > 0 ? MAX_BACKOFF : backoff.multipliedBy(2);
		}
	}

	private void pollUntilFailure(TagSource source) throws Exception {
		long period = machine.pollInterval().toNanos();
		long next = System.nanoTime();
		while (running) {
			Map<String, Object> values = source.read();
			Instant now = clock.instant();
			publish(tracker.accept(now, values));
			next += period;
			long wait = next - System.nanoTime();
			if (wait > 0) {
				Thread.sleep(Duration.ofNanos(wait));
			}
			else {
				// fell behind (slow PLC or network): skip the missed cycles instead of bursting
				next = System.nanoTime();
			}
		}
	}

	private void publish(List<MachineEvent> events) {
		for (MachineEvent event : events) {
			if (event instanceof MachineEvent.StateChange change) {
				log.info("{}: {} (fault {})", machine.id(), change.state(), change.faultCode());
			}
			uplink.accept(event);
		}
	}

	public void stop() {
		running = false;
	}

	private static boolean sleep(Duration duration) {
		try {
			Thread.sleep(duration);
			return true;
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			return false;
		}
	}

	private static String message(Throwable ex) {
		Throwable cause = ex;
		while (cause.getCause() != null && cause.getMessage() == null) {
			cause = cause.getCause();
		}
		return cause.getClass().getSimpleName() + ": " + cause.getMessage();
	}
}
