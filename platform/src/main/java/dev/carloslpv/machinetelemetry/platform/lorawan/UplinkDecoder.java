package dev.carloslpv.machinetelemetry.platform.lorawan;

import java.util.ArrayList;
import java.util.List;

/**
 * Decodes payload version 1 (layout in the gateway's PayloadCodec). Decoding here instead of trusting the TTN
 * formatter's output means the platform never depends on what someone typed in the TTN console.
 */
public final class UplinkDecoder {

	private static final String[] STATES = { "IDLE", "RUNNING", "FAULT", "NO_DATA" };

	public record Uplink(int version, String state, int faultCode, int goodCount16, int rejectCount16, int secondsInState,
			List<Double> analogs) {
	}

	private UplinkDecoder() {
	}

	public static Uplink decode(byte[] bytes) {
		if (bytes.length < 9 || (bytes.length - 9) % 2 != 0) {
			throw new IllegalArgumentException("unexpected payload length " + bytes.length);
		}
		int version = (bytes[0] & 0xFF) >> 4;
		if (version != 1) {
			throw new IllegalArgumentException("unknown payload version " + version);
		}
		int state = bytes[0] & 0x0F;
		if (state >= STATES.length) {
			throw new IllegalArgumentException("unknown state " + state);
		}
		List<Double> analogs = new ArrayList<>();
		for (int i = 9; i < bytes.length; i += 2) {
			short raw = (short) u16(bytes, i);
			analogs.add(raw == Short.MIN_VALUE ? null : raw / 10.0);
		}
		return new Uplink(version, STATES[state], u16(bytes, 1), u16(bytes, 3), u16(bytes, 5), u16(bytes, 7), analogs);
	}

	private static int u16(byte[] b, int i) {
		return ((b[i] & 0xFF) << 8) | (b[i + 1] & 0xFF);
	}
}
