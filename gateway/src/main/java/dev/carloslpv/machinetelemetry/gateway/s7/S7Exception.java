package dev.carloslpv.machinetelemetry.gateway.s7;

import java.io.IOException;

/** The PLC answered, but not with what was asked for. */
public final class S7Exception extends IOException {

	public S7Exception(String message) {
		super(message);
	}
}
