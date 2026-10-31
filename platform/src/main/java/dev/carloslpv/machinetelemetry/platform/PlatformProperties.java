package dev.carloslpv.machinetelemetry.platform;

import java.time.ZoneId;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * @param apiKey key the REST API expects in {@code X-API-Key}
 * @param zone time zone that "today" means for the default report window
 */
@ConfigurationProperties("platform")
public record PlatformProperties(String apiKey, @DefaultValue("America/Sao_Paulo") ZoneId zone, Mqtt mqtt, Ttn ttn) {

	public PlatformProperties {
		if (apiKey == null || apiKey.length() < 16) {
			throw new IllegalArgumentException("platform.api-key must be set and at least 16 characters long");
		}
	}

	public record Mqtt(@DefaultValue("true") boolean enabled, @DefaultValue("tcp://localhost:1883") String uri,
			@DefaultValue("machine-telemetry-platform") String clientId, String username, String password,
			@DefaultValue("mt") String topicPrefix) {
	}

	/**
	 * @param webhookToken value TTN sends in the {@code X-Webhook-Token} header (set as an additional header of the
	 * webhook)
	 * @param applicationId TTN application whose uplinks are accepted, like the TCC's payload parser checked it
	 */
	public record Ttn(String webhookToken, String applicationId) {
	}
}
