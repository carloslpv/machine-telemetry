package dev.carloslpv.machinetelemetry.gateway.model;

/**
 * What the machine is doing, as the gateway sees it. {@code NO_DATA} is the gateway's own state: it could not read
 * the PLC, so it does not know.
 */
public enum MachineState {

	IDLE(0), RUNNING(1), FAULT(2), NO_DATA(3);

	/** Value used in the LoRaWAN payload. */
	public final int code;

	MachineState(int code) {
		this.code = code;
	}
}
