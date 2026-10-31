package dev.carloslpv.machinetelemetry.gateway.source;

import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import org.eclipse.milo.opcua.sdk.client.OpcUaClient;
import org.eclipse.milo.opcua.sdk.client.api.identity.AnonymousProvider;
import org.eclipse.milo.opcua.sdk.client.api.identity.IdentityProvider;
import org.eclipse.milo.opcua.sdk.client.api.identity.UsernameProvider;
import org.eclipse.milo.opcua.stack.core.Identifiers;
import org.eclipse.milo.opcua.stack.core.security.SecurityPolicy;
import org.eclipse.milo.opcua.stack.core.types.builtin.DataValue;
import org.eclipse.milo.opcua.stack.core.types.builtin.LocalizedText;
import org.eclipse.milo.opcua.stack.core.types.builtin.NodeId;
import org.eclipse.milo.opcua.stack.core.types.enumerated.TimestampsToReturn;
import org.eclipse.milo.opcua.stack.core.types.structured.EndpointDescription;
import org.eclipse.milo.opcua.stack.core.util.EndpointUtil;

import dev.carloslpv.machinetelemetry.gateway.config.GatewayConfig;
import dev.carloslpv.machinetelemetry.gateway.config.GatewayConfig.Tag;

import static org.eclipse.milo.opcua.stack.core.types.builtin.unsigned.Unsigned.uint;

/**
 * Reads the tags from the CPU's OPC UA server with one Read service call per cycle.
 * <p>
 * Polling instead of a subscription keeps OPC UA and S7 behind the same contract: every cycle the gateway gets a
 * complete, consistent picture of the machine, which is what state detection needs. Node ids written without a
 * namespace are resolved against {@code namespace-uri} after connecting, because the namespace index is not stable
 * across servers (the S7-1200 uses 3, other servers do not).
 */
final class OpcUaSource implements TagSource {

	private static final long TIMEOUT_SECONDS = 10;

	private final GatewayConfig.Source config;

	private final Map<String, Tag> tags;

	private OpcUaClient client;

	private List<String> keys;

	private List<NodeId> nodeIds;

	OpcUaSource(GatewayConfig.Source config, Map<String, Tag> tags) {
		this.config = config;
		this.tags = new LinkedHashMap<>(tags);
	}

	@Override
	public void connect() throws Exception {
		URI endpoint = URI.create(config.endpoint());
		IdentityProvider identity = config.username() == null || config.username().isBlank() ? new AnonymousProvider()
				: new UsernameProvider(config.username(), config.password() == null ? "" : config.password());
		OpcUaClient created = OpcUaClient.create(config.endpoint(), endpoints -> selectEndpoint(endpoints, endpoint),
				builder -> builder.setApplicationName(LocalizedText.english("machine-telemetry gateway"))
					.setApplicationUri("urn:carloslpv:machine-telemetry:gateway")
					.setIdentityProvider(identity)
					.setRequestTimeout(uint(5_000))
					.build());
		try {
			created.connect().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
			resolveNodeIds(created);
		}
		catch (Exception ex) {
			created.disconnect();
			throw ex;
		}
		client = created;
	}

	/**
	 * Picks the unsecured endpoint and points it at the host we were configured with: servers behind NAT or in a
	 * container often advertise an address the gateway cannot reach (0.0.0.0, an internal host name).
	 */
	private static Optional<EndpointDescription> selectEndpoint(List<EndpointDescription> endpoints, URI configured) {
		return endpoints.stream()
			.filter(e -> SecurityPolicy.None.getUri().equals(e.getSecurityPolicyUri()))
			.findFirst()
			.map(e -> EndpointUtil.updateUrl(e, configured.getHost(), configured.getPort() < 0 ? 4840 : configured.getPort()));
	}

	private void resolveNodeIds(OpcUaClient opened) throws Exception {
		Integer namespaceIndex = null;
		if (config.namespaceUri() != null && !config.namespaceUri().isBlank()) {
			DataValue value = opened.readValue(0, TimestampsToReturn.Neither, Identifiers.Server_NamespaceArray)
				.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
			List<String> namespaces = Arrays.asList((String[]) value.getValue().getValue());
			int index = namespaces.indexOf(config.namespaceUri());
			if (index < 0) {
				throw new IllegalStateException("the server has no namespace " + config.namespaceUri() + "; it has " + namespaces);
			}
			namespaceIndex = index;
		}
		keys = new ArrayList<>(tags.keySet());
		nodeIds = new ArrayList<>();
		for (String key : keys) {
			String address = tags.get(key).address();
			if (address.startsWith("ns=") || address.startsWith("i=") || address.startsWith("s=")) {
				nodeIds.add(NodeId.parse(address));
			}
			else if (namespaceIndex != null) {
				nodeIds.add(new NodeId(namespaceIndex, address));
			}
			else {
				throw new IllegalArgumentException("tag " + key + " has no namespace: set namespace-uri or write ns=<n>;s=" + address);
			}
		}
	}

	@Override
	public Map<String, Object> read() throws Exception {
		if (client == null) {
			throw new IllegalStateException("not connected");
		}
		List<DataValue> values = client.readValues(0, TimestampsToReturn.Neither, nodeIds)
			.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
		Map<String, Object> out = new LinkedHashMap<>();
		for (int i = 0; i < keys.size(); i++) {
			DataValue value = values.get(i);
			if (value.getStatusCode() == null || !value.getStatusCode().isGood()) {
				throw new IllegalStateException("bad status " + value.getStatusCode() + " reading " + nodeIds.get(i));
			}
			out.put(keys.get(i), normalize(value.getValue().getValue(), nodeIds.get(i)));
		}
		return out;
	}

	static Object normalize(Object value, Object node) {
		return switch (value) {
			case Boolean b -> b;
			case Float f -> f.doubleValue();
			case Double d -> d;
			// Milo's unsigned types (UShort, UInteger, ULong) are Numbers too
			case Number n -> n.longValue();
			case null -> throw new IllegalStateException(node + " has no value");
			default -> throw new IllegalStateException(node + " has a " + value.getClass().getSimpleName() + ", expected a number or a bool");
		};
	}

	@Override
	public void close() {
		if (client != null) {
			try {
				client.disconnect().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
			}
			catch (Exception ignored) {
				// closing anyway
			}
			client = null;
		}
	}

	@Override
	public String describe() {
		return "OPC UA " + config.endpoint();
	}
}
