package dev.carloslpv.machinetelemetry.platform.ingest;

/**
 * Units produced between two readings of a PLC counter.
 * <p>
 * Counters arrive as raw values of {@code bits} bits (32 for a DINT over MQTT, 16 over LoRaWAN), so the difference
 * is taken modulo 2^bits: a counter that wrapped from 4294967295 to 3 produced 4 units. A difference larger than
 * half the range is not a wrap but a reset (someone zeroed the counter), and then the new value is what was produced
 * since.
 */
public final class Counters {

	private Counters() {
	}

	/** Units since the last reading, or null when there is no comparable last reading (first message, width changed). */
	public static Long delta(Long last, Integer lastBits, long current, int bits) {
		if (last == null || lastBits == null || lastBits != bits) {
			return null;
		}
		if (bits >= 64) {
			return current >= last ? current - last : current;
		}
		long modulus = 1L << bits;
		long mask = modulus - 1;
		long d = ((current & mask) - (last & mask)) & mask;
		return d > modulus / 2 ? current & mask : d;
	}
}
