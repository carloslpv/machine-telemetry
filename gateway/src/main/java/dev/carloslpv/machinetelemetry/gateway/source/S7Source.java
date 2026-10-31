package dev.carloslpv.machinetelemetry.gateway.source;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

import dev.carloslpv.machinetelemetry.gateway.config.GatewayConfig;
import dev.carloslpv.machinetelemetry.gateway.config.GatewayConfig.Tag;
import dev.carloslpv.machinetelemetry.gateway.s7.S7Address;
import dev.carloslpv.machinetelemetry.gateway.s7.S7Client;

/**
 * Reads the tags over S7 PUT/GET. All tags of a data block come from one contiguous read covering the lowest to
 * the highest address, so the values of one cycle belong to the same PLC scan as far as the protocol allows.
 */
final class S7Source implements TagSource {

	private static final Duration TIMEOUT = Duration.ofSeconds(5);

	private final GatewayConfig.Source config;

	private final Map<String, S7Address> addresses = new LinkedHashMap<>();

	/** For each data block, the first byte and the number of bytes to read. */
	private final Map<Integer, int[]> ranges = new TreeMap<>();

	private S7Client client;

	S7Source(GatewayConfig.Source config, Map<String, Tag> tags) {
		this.config = config;
		tags.forEach((key, tag) -> addresses.put(key, S7Address.parse(tag.address(), tag.type())));
		for (S7Address address : addresses.values()) {
			ranges.merge(address.db(), new int[] { address.offset(), address.offset() + address.size() },
					(a, b) -> new int[] { Math.min(a[0], b[0]), Math.max(a[1], b[1]) });
		}
	}

	@Override
	public void connect() throws Exception {
		S7Client created = new S7Client();
		created.connect(config.host(), config.port(), config.rack(), config.slot(), TIMEOUT);
		client = created;
	}

	@Override
	public Map<String, Object> read() throws Exception {
		if (client == null) {
			throw new IllegalStateException("not connected");
		}
		Map<Integer, byte[]> blocks = new TreeMap<>();
		for (Map.Entry<Integer, int[]> range : ranges.entrySet()) {
			int[] r = range.getValue();
			blocks.put(range.getKey(), client.readDb(range.getKey(), r[0], r[1] - r[0]));
		}
		Map<String, Object> out = new LinkedHashMap<>();
		addresses.forEach((key, address) -> out.put(key, address.decode(blocks.get(address.db()), ranges.get(address.db())[0])));
		return out;
	}

	@Override
	public void close() {
		if (client != null) {
			client.close();
			client = null;
		}
	}

	@Override
	public String describe() {
		return "S7 " + config.host() + ":" + config.port() + " rack " + config.rack() + " slot " + config.slot();
	}
}
