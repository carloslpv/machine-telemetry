package dev.carloslpv.machinetelemetry.gateway.uplink;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import dev.carloslpv.machinetelemetry.gateway.config.GatewayConfig;
import dev.carloslpv.machinetelemetry.gateway.lorawan.AtModem;
import dev.carloslpv.machinetelemetry.gateway.lorawan.PayloadCodec;
import dev.carloslpv.machinetelemetry.gateway.model.MachineEvent;
import dev.carloslpv.machinetelemetry.gateway.model.MachineEvent.AnalogStats;
import dev.carloslpv.machinetelemetry.gateway.model.MachineEvent.StateChange;
import dev.carloslpv.machinetelemetry.gateway.model.MachineEvent.Telemetry;
import dev.carloslpv.machinetelemetry.gateway.model.MachineState;

/**
 * Sends the latest picture of each machine over LoRaWAN, for sites without network (a silo at the far end of a
 * property, a pump house).
 * <p>
 * LoRaWAN is not a pipe for every event: airtime is limited and the module refuses to send faster than about every
 * six seconds (the TCC measured "busy" errors below that). So this uplink keeps only the latest state of each machine
 * and sends it when the state changes or every {@code interval}, never closer than {@code min-interval}. History is
 * not queued; the payload carries totals and time-in-state, so a lost uplink is recovered by the next one. Each
 * machine goes out on its own FPort (1 for the first machine, 2 for the second, ...).
 */
public final class LoraWanUplink implements Uplink {

	private static final Logger log = LoggerFactory.getLogger(LoraWanUplink.class);

	private static final int MAX_BUSY_RETRIES = 5;

	private final GatewayConfig.LoraWan config;

	private final AtModem modem;

	private final Clock clock;

	private final Map<String, Snapshot> machines = new LinkedHashMap<>();

	private volatile boolean running;

	private Thread sender;

	private Instant lastSend = Instant.EPOCH;

	public LoraWanUplink(GatewayConfig gateway, AtModem modem, Clock clock) {
		this.config = gateway.uplink().lorawan();
		this.modem = modem;
		this.clock = clock;
		int port = 1;
		for (GatewayConfig.Machine machine : gateway.machines()) {
			machines.put(machine.id(), new Snapshot(port++));
		}
	}

	private static final class Snapshot {

		final int port;

		MachineState state = MachineState.NO_DATA;

		int faultCode;

		Instant since;

		long goodCount;

		long rejectCount;

		Map<String, AnalogStats> analogs = Map.of();

		boolean changed;

		Instant lastSent = Instant.EPOCH;

		Snapshot(int port) {
			this.port = port;
		}
	}

	@Override
	public synchronized void accept(MachineEvent event) {
		Snapshot s = machines.get(event.machineId());
		if (s == null) {
			return;
		}
		switch (event) {
			case StateChange c -> {
				s.state = c.state();
				s.faultCode = c.faultCode();
				s.since = c.at();
				s.changed = true;
			}
			case Telemetry t -> {
				if (t.goodCount() != null) {
					s.goodCount = t.goodCount();
				}
				if (t.rejectCount() != null) {
					s.rejectCount = t.rejectCount();
				}
				s.analogs = t.analogs();
			}
		}
		notifyAll();
	}

	@Override
	public void start() {
		running = true;
		sender = Thread.ofVirtual().name("lorawan-sender").start(this::sendLoop);
	}

	private void sendLoop() {
		while (running) {
			try {
				Map.Entry<String, Snapshot> due = waitForDue();
				if (due == null) {
					continue;
				}
				send(due.getKey(), due.getValue());
			}
			catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
				return;
			}
			catch (Exception ex) {
				log.warn("LoRaWAN uplink: {}", ex.getMessage());
			}
		}
	}

	/** Blocks until a machine has to be sent and the modem may send again. */
	private synchronized Map.Entry<String, Snapshot> waitForDue() throws InterruptedException {
		Instant now = clock.instant();
		Instant modemFree = lastSend.plus(config.minInterval());
		Map.Entry<String, Snapshot> due = null;
		Instant earliest = null;
		for (Map.Entry<String, Snapshot> entry : machines.entrySet()) {
			Snapshot s = entry.getValue();
			if (s.since == null) {
				continue; // nothing seen yet
			}
			Instant when = s.changed ? s.lastSent : s.lastSent.plus(config.interval());
			if (earliest == null || when.isBefore(earliest)) {
				earliest = when;
				due = entry;
			}
		}
		if (earliest == null) {
			wait(1_000);
			return null;
		}
		Instant at = earliest.isAfter(modemFree) ? earliest : modemFree;
		long waitMillis = Duration.between(now, at).toMillis();
		if (waitMillis > 0) {
			wait(Math.min(waitMillis, 1_000));
			return null;
		}
		return due;
	}

	private void send(String machineId, Snapshot s) throws Exception {
		byte[] payload;
		synchronized (this) {
			payload = PayloadCodec.encode(uplinkOf(s));
			s.changed = false;
			s.lastSent = clock.instant();
		}
		for (int attempt = 1; attempt <= MAX_BUSY_RETRIES; attempt++) {
			AtModem.Result result = modem.send(s.port, payload);
			synchronized (this) {
				lastSend = clock.instant();
			}
			switch (result) {
				case SENT -> {
					log.info("{}: sent {} bytes on port {}", machineId, payload.length, s.port);
					return;
				}
				case BUSY -> {
					log.debug("{}: modem busy, attempt {}", machineId, attempt);
					Thread.sleep(config.minInterval());
				}
				case ERROR, TIMEOUT -> {
					log.warn("{}: uplink failed ({}); the next one carries the same totals", machineId, result);
					return;
				}
			}
		}
		log.warn("{}: modem stayed busy for {} attempts", machineId, MAX_BUSY_RETRIES);
	}

	private PayloadCodec.Uplink uplinkOf(Snapshot s) {
		List<Double> analogs = new ArrayList<>();
		for (String name : config.analogs()) {
			AnalogStats stats = s.analogs.get(name);
			analogs.add(stats == null ? null : stats.avg());
		}
		long seconds = s.since == null ? 0 : Duration.between(s.since, clock.instant()).toSeconds();
		return new PayloadCodec.Uplink(s.state, s.faultCode, s.goodCount, s.rejectCount, seconds, analogs);
	}

	@Override
	public void close() {
		running = false;
		if (sender != null) {
			sender.interrupt();
		}
	}
}
