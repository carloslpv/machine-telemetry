package dev.carloslpv.machinetelemetry.gateway.lorawan;

import java.nio.ByteBuffer;
import java.util.List;

import dev.carloslpv.machinetelemetry.gateway.model.MachineState;

/**
 * Binary uplink payload, version 1. The TCC sent the value as ASCII text; packing it as integers fits a machine's
 * whole state in 9 bytes plus 2 per analog value, which matters at LoRaWAN data rates (51 bytes at DR0 in AU915).
 *
 * <pre>
 * byte 0      version (high nibble, 1) | state (low nibble: 0 idle, 1 running, 2 fault, 3 no data)
 * bytes 1-2   fault code, uint16
 * bytes 3-4   good count modulo 65536, uint16
 * bytes 5-6   reject count modulo 65536, uint16
 * bytes 7-8   seconds in the current state, uint16 (saturates at 65535)
 * bytes 9..   analog values in configured order, int16, value x 10
 * </pre>
 *
 * Counters go as absolute values cut to 16 bits, not as deltas: a lost uplink then costs nothing, because the next
 * one carries the total and the platform takes the difference modulo 65536. Seconds in state let the platform place
 * a state change in time even though uplinks are minutes apart. The same layout is decoded by
 * lorawan/ttn-formatter.js and by the platform; lorawan/test-vectors.json keeps the three in agreement.
 */
public final class PayloadCodec {

	public static final int VERSION = 1;

	public record Uplink(MachineState state, int faultCode, long goodCount, long rejectCount, long secondsInState,
			List<Double> analogs) {
	}

	private PayloadCodec() {
	}

	public static byte[] encode(Uplink uplink) {
		ByteBuffer buffer = ByteBuffer.allocate(9 + 2 * uplink.analogs().size());
		buffer.put((byte) (VERSION << 4 | uplink.state().code));
		buffer.putShort((short) clamp(uplink.faultCode(), 0, 0xFFFF));
		buffer.putShort((short) (uplink.goodCount() & 0xFFFF));
		buffer.putShort((short) (uplink.rejectCount() & 0xFFFF));
		buffer.putShort((short) clamp(uplink.secondsInState(), 0, 0xFFFF));
		for (Double value : uplink.analogs()) {
			// -32768 is reserved for "no value", so real values saturate at -32767
			buffer.putShort(value == null || value.isNaN() ? Short.MIN_VALUE
					: (short) clamp(Math.round(value * 10), Short.MIN_VALUE + 1, Short.MAX_VALUE));
		}
		return buffer.array();
	}

	private static long clamp(long value, long min, long max) {
		return Math.max(min, Math.min(max, value));
	}
}
