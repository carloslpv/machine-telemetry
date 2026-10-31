package dev.carloslpv.machinetelemetry.gateway.uplink;

import java.util.function.Consumer;

import dev.carloslpv.machinetelemetry.gateway.model.MachineEvent;

/**
 * Where machine events go. {@link #accept} is called from the machine threads and must not block on the network;
 * sending happens on the uplink's own thread.
 */
public interface Uplink extends Consumer<MachineEvent>, AutoCloseable {

	void start();

	@Override
	void close();
}
