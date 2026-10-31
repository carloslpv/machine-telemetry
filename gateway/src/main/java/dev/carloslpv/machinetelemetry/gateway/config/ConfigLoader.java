package dev.carloslpv.machinetelemetry.gateway.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import tools.jackson.core.JsonParser;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.ValueDeserializer;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.module.SimpleModule;
import tools.jackson.dataformat.yaml.YAMLMapper;

/**
 * Reads the YAML configuration. Keys are kebab-case ({@code poll-interval}); values may reference environment
 * variables as {@code ${NAME}} or {@code ${NAME:default}}, so passwords stay out of the file.
 */
public final class ConfigLoader {

	private static final Pattern DURATION = Pattern.compile("(\\d+)\\s*(ms|s|m|h)");

	private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([A-Za-z0-9_]+)(?::([^}]*))?}");

	private ConfigLoader() {
	}

	public static GatewayConfig load(Path file) throws IOException {
		return parse(Files.readString(file), System.getenv());
	}

	static GatewayConfig parse(String yaml, Map<String, String> env) {
		ObjectMapper mapper = YAMLMapper.builder()
			.propertyNamingStrategy(PropertyNamingStrategies.KEBAB_CASE)
			.enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
			.addModule(new SimpleModule().addDeserializer(Duration.class, new DurationDeserializer()))
			.build();
		return mapper.readValue(resolve(yaml, env), GatewayConfig.class);
	}

	static String resolve(String text, Map<String, String> env) {
		Matcher matcher = PLACEHOLDER.matcher(text);
		StringBuilder out = new StringBuilder();
		while (matcher.find()) {
			String value = env.get(matcher.group(1));
			if (value == null) {
				value = matcher.group(2);
			}
			if (value == null) {
				throw new IllegalArgumentException("environment variable " + matcher.group(1) + " is not set");
			}
			matcher.appendReplacement(out, Matcher.quoteReplacement(value));
		}
		matcher.appendTail(out);
		return out.toString();
	}

	/** Parses durations written the way people write them in a config file: 250ms, 10s, 5m, 1h. */
	static Duration parseDuration(String text) {
		Matcher matcher = DURATION.matcher(text.trim());
		if (!matcher.matches()) {
			throw new IllegalArgumentException("'" + text + "' is not a duration such as 250ms, 10s, 5m or 1h");
		}
		long amount = Long.parseLong(matcher.group(1));
		return switch (matcher.group(2)) {
			case "ms" -> Duration.ofMillis(amount);
			case "s" -> Duration.ofSeconds(amount);
			case "m" -> Duration.ofMinutes(amount);
			default -> Duration.ofHours(amount);
		};
	}

	private static final class DurationDeserializer extends ValueDeserializer<Duration> {

		@Override
		public Duration deserialize(JsonParser parser, DeserializationContext context) {
			return parseDuration(parser.getValueAsString());
		}
	}

	/** JSON mapper shared by the uplinks, with the same naming the platform expects (camelCase). */
	public static JsonMapper json() {
		return JsonMapper.builder().build();
	}
}
