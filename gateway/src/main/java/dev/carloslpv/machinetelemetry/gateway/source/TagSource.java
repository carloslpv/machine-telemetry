package dev.carloslpv.machinetelemetry.gateway.source;

import java.util.Map;

import dev.carloslpv.machinetelemetry.gateway.config.GatewayConfig;
import dev.carloslpv.machinetelemetry.gateway.config.GatewayConfig.Tag;

/**
 * A PLC the gateway reads cyclically. Implementations are not thread-safe: each machine has its own source and
 * reads it from one thread.
 */
public interface TagSource extends AutoCloseable {

	void connect() throws Exception;

	/**
	 * Reads every tag in one round trip. Values are {@link Boolean} for bits, {@link Long} for integers and
	 * {@link Double} for floating point, already in the units of the PLC (no scaling).
	 */
	Map<String, Object> read() throws Exception;

	@Override
	void close();

	/** What the gateway calls the source in logs, without credentials. */
	String describe();

	static TagSource create(GatewayConfig.Source source, Map<String, Tag> tags) {
		return switch (source.type()) {
			case "opcua" -> new OpcUaSource(source, tags);
			case "s7" -> new S7Source(source, tags);
			default -> throw new IllegalArgumentException(source.type());
		};
	}
}
