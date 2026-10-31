package dev.carloslpv.machinetelemetry.gateway.lorawan;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import dev.carloslpv.machinetelemetry.gateway.model.MachineState;

import static org.assertj.core.api.Assertions.assertThat;

/** The encoder against lorawan/test-vectors.json, the file the TTN formatter and the platform are tested with too. */
class PayloadCodecTests {

	@Test
	void encodesEveryTestVector() {
		JsonNode vectors = JsonMapper.builder().build().readTree(Path.of("../lorawan/test-vectors.json").toFile()).get("vectors");
		assertThat(vectors.size()).isGreaterThanOrEqualTo(4);
		for (JsonNode v : vectors) {
			List<Double> analogs = new ArrayList<>();
			v.get("analogs").forEach(a -> analogs.add(a.isNull() ? null : a.asDouble()));
			PayloadCodec.Uplink uplink = new PayloadCodec.Uplink(MachineState.valueOf(v.get("state").asString()), v.get("faultCode").asInt(),
					v.get("goodCount").asLong(), v.get("rejectCount").asLong(), v.get("secondsInState").asLong(), analogs);
			assertThat(HexFormat.of().withUpperCase().formatHex(PayloadCodec.encode(uplink))).as(v.get("name").asString())
				.isEqualTo(v.get("hex").asString());
		}
	}
}
