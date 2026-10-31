package dev.carloslpv.machinetelemetry.platform.lorawan;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Against lorawan/test-vectors.json, the same file the gateway encoder and the TTN formatter are tested with. */
class UplinkDecoderTests {

	@Test
	void decodesEveryTestVector() {
		JsonNode vectors = JsonMapper.builder().build().readTree(Path.of("../lorawan/test-vectors.json").toFile()).get("vectors");
		for (JsonNode v : vectors) {
			JsonNode d = v.get("decoded");
			List<Double> analogs = new ArrayList<>();
			d.get("analogs").forEach(a -> analogs.add(a.isNull() ? null : a.asDouble()));
			assertThat(UplinkDecoder.decode(HexFormat.of().parseHex(v.get("hex").asString()))).as(v.get("name").asString())
				.isEqualTo(new UplinkDecoder.Uplink(1, d.get("state").asString(), d.get("faultCode").asInt(), d.get("goodCount16").asInt(),
						d.get("rejectCount16").asInt(), d.get("secondsInState").asInt(), analogs));
		}
	}

	@Test
	void refusesWhatItCannotRead() {
		assertThatThrownBy(() -> UplinkDecoder.decode(new byte[] { 0x11, 0 })).hasMessageContaining("length");
		assertThatThrownBy(() -> UplinkDecoder.decode(new byte[] { 0x21, 0, 0, 0, 0, 0, 0, 0, 0 })).hasMessageContaining("version");
		assertThatThrownBy(() -> UplinkDecoder.decode(new byte[] { 0x17, 0, 0, 0, 0, 0, 0, 0, 0 })).hasMessageContaining("state");
	}
}
