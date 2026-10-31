package dev.carloslpv.machinetelemetry.platform;

import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import dev.carloslpv.machinetelemetry.platform.ingest.IngestService;
import dev.carloslpv.machinetelemetry.platform.ingest.IngestService.Result;
import dev.carloslpv.machinetelemetry.platform.ingest.IngestService.StateEvent;
import dev.carloslpv.machinetelemetry.platform.ingest.IngestService.TelemetryEvent;
import dev.carloslpv.machinetelemetry.platform.ingest.Messages.Analog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.closeTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestDatabase.class)
class IngestIntegrationTests {

	static final Instant T0 = Instant.parse("2026-10-30T11:00:00Z");

	static final String KEY = "test-key-0123456789";

	@Autowired
	IngestService ingest;

	@Autowired
	JdbcClient jdbc;

	@Autowired
	MockMvc mvc;

	private long seq;

	@BeforeEach
	void cleanDatabase() throws Exception {
		jdbc.sql("TRUNCATE machine, received_message, gateway CASCADE").update();
		register("ENS-01", "{\"site\":\"joacaba\",\"name\":\"Ensacadeira 1\",\"idealCycleSeconds\":6}");
		register("ENS-03", """
				{"site":"joacaba","name":"Ensacadeira 3","idealCycleSeconds":6,
				 "lorawan":{"devEui":"70b3d57ed0000001","port":1,"analogs":["motor-current","motor-temp"]}}""");
		mvc.perform(put("/api/v1/machines/ENS-01/fault-codes/102").header("X-API-Key", KEY)
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"description\":\"Saco travado na boca de ensaque\"}")).andExpect(status().isOk());
	}

	private void register(String id, String body) throws Exception {
		mvc.perform(put("/api/v1/machines/" + id).header("X-API-Key", KEY).contentType(MediaType.APPLICATION_JSON).content(body))
			.andExpect(status().isOk());
	}

	private Result state(Instant at, String state, int code) {
		return ingest.applyState("gw-1", Long.toString(++seq), "joacaba", new StateEvent("ENS-01", at, state, code));
	}

	private Result telemetry(Instant at, String state, long good, long reject) {
		return telemetry(at, state, 0, good, reject);
	}

	private Result telemetry(Instant at, String state, int faultCode, long good, long reject) {
		return ingest.applyTelemetry("gw-1", Long.toString(++seq), "joacaba", new TelemetryEvent("ENS-01", at, state, faultCode, good, reject, 32,
				Map.of("motor-temp", new Analog(70.0, 69.5, 70.5, 70.1, 40))));
	}

	/** One hour: 40 min running, a 10 min jam, 10 min running; telemetry every minute so the machine never goes stale. */
	private void scriptedHour() {
		state(T0, "RUNNING", 0);
		long good = 1000;
		long reject = 50;
		for (int minute = 1; minute <= 60; minute++) {
			Instant at = T0.plus(Duration.ofMinutes(minute));
			if (minute == 40) {
				state(at, "FAULT", 102);
			}
			if (minute == 50) {
				state(at, "RUNNING", 0);
			}
			boolean running = minute <= 40 || minute > 50;
			if (running) {
				good += 7; // 3000 s running at 6 s ideal = 500 units; 7+1 per minute over 50 minutes = 400 units
				reject += minute % 5 == 0 ? 1 : 0;
			}
			boolean faulted = minute >= 40 && minute < 50;
			telemetry(at, faulted ? "FAULT" : "RUNNING", faulted ? 102 : 0, good, reject);
		}
		state(T0.plus(Duration.ofMinutes(60)), "IDLE", 0);
	}

	@Test
	void computesOeeFromTheStateHistoryAndTheCounters() throws Exception {
		scriptedHour();
		// the first telemetry only sets the baseline; the one at minute 60 falls outside [from, to). Counted: minutes 2 to 59
		// running, 48 x 7 good, and a reject every fifth running minute (5, 10, ... 40, 55): 9
		mvc.perform(get("/api/v1/machines/ENS-01/oee").header("X-API-Key", KEY)
			.param("from", T0.toString())
			.param("to", T0.plus(Duration.ofHours(1)).toString()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.runningSeconds", closeTo(3000.0, 0.01)))
			.andExpect(jsonPath("$.faultSeconds", closeTo(600.0, 0.01)))
			.andExpect(jsonPath("$.good", is(336)))
			.andExpect(jsonPath("$.reject", is(9)))
			.andExpect(jsonPath("$.availability", closeTo(3000.0 / 3600, 1e-9)))
			.andExpect(jsonPath("$.performance", closeTo(345 * 6.0 / 3000, 1e-9)))
			.andExpect(jsonPath("$.quality", closeTo(336.0 / 345, 1e-9)))
			.andExpect(jsonPath("$.oee", closeTo(3000.0 / 3600 * (345 * 6.0 / 3000) * (336.0 / 345), 1e-9)));
	}

	@Test
	void listsStopsWithTheirCauseAndRanksThem() throws Exception {
		scriptedHour();
		mvc.perform(get("/api/v1/machines/ENS-01/stops").header("X-API-Key", KEY)
			.param("from", T0.toString())
			.param("to", T0.plus(Duration.ofHours(1)).toString()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$", hasSize(1)))
			.andExpect(jsonPath("$[0].faultCode", is(102)))
			.andExpect(jsonPath("$[0].description", is("Saco travado na boca de ensaque")))
			.andExpect(jsonPath("$[0].seconds", closeTo(600.0, 0.01)));
		mvc.perform(get("/api/v1/machines/ENS-01/stops/pareto").header("X-API-Key", KEY)
			.param("from", T0.toString())
			.param("to", T0.plus(Duration.ofHours(1)).toString()))
			.andExpect(jsonPath("$[0].stops", is(1)));
	}

	@Test
	void aRedeliveredMessageChangesNothing() {
		assertThat(ingest.applyState("gw-1", "7", "joacaba", new StateEvent("ENS-01", T0, "RUNNING", 0))).isEqualTo(Result.APPLIED);
		assertThat(ingest.applyState("gw-1", "7", "joacaba", new StateEvent("ENS-01", T0, "RUNNING", 0))).isEqualTo(Result.DUPLICATE);
		// the same sequence number from another gateway is another message
		assertThat(ingest.applyState("gw-2", "7", "joacaba", new StateEvent("ENS-01", T0.plusSeconds(1), "IDLE", 0))).isEqualTo(Result.APPLIED);
		assertThat(intervals()).isEqualTo(2);
	}

	@Test
	void aMachineOfAnotherSiteOrUnknownIsRejected() {
		assertThat(ingest.applyState("gw-1", "1", "chapeco", new StateEvent("ENS-01", T0, "RUNNING", 0))).isEqualTo(Result.REJECTED);
		assertThat(ingest.applyState("gw-1", "2", "joacaba", new StateEvent("XYZ", T0, "RUNNING", 0))).isEqualTo(Result.REJECTED);
		assertThat(intervals()).isZero();
	}

	@Test
	void silenceLongerThanTheMachineMayStayQuietBecomesNoData() {
		state(T0, "RUNNING", 0);
		telemetry(T0.plusSeconds(60), "RUNNING", 10, 0);
		// the gateway lost power for 20 minutes and comes back with the machine still running
		state(T0.plus(Duration.ofMinutes(21)), "RUNNING", 0);
		assertThat(jdbc.sql("SELECT state || ' ' || extract(epoch FROM coalesce(ended_at, started_at) - started_at)::int FROM state_interval ORDER BY started_at")
			.query(String.class)
			.list()).containsExactly("RUNNING 60", "NO_DATA 1200", "RUNNING 0");
	}

	@Test
	void anOlderMessageThanTheLastOneIsLate() {
		state(T0.plusSeconds(10), "RUNNING", 0);
		assertThat(state(T0, "IDLE", 0)).isEqualTo(Result.LATE);
		assertThat(intervals()).isEqualTo(1);
	}

	@Test
	void telemetryRepairsAStateChangeThatNeverArrived() {
		state(T0, "RUNNING", 0);
		// the IDLE event was dropped by a full outbox; the next telemetry says IDLE
		telemetry(T0.plusSeconds(10), "IDLE", 0, 0);
		assertThat(jdbc.sql("SELECT state FROM state_interval WHERE ended_at IS NULL").query(String.class).single()).isEqualTo("IDLE");
	}

	@Test
	void storesAnalogStatistics() {
		telemetry(T0, "RUNNING", 0, 0);
		assertThat(jdbc.sql("SELECT avg FROM analog_sample WHERE name = 'motor-temp'").query(Double.class).single()).isEqualTo(70.0);
	}

	@Test
	void theApiNeedsTheKey() throws Exception {
		mvc.perform(get("/api/v1/machines")).andExpect(status().isUnauthorized());
		mvc.perform(get("/api/v1/machines").header("X-API-Key", "wrong")).andExpect(status().isUnauthorized());
		mvc.perform(get("/api/v1/machines").header("X-API-Key", KEY)).andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(2)));
		mvc.perform(get("/actuator/health")).andExpect(status().isOk());
	}

	// LoRaWAN through the TTN webhook

	private static String ttnUplink(String app, String devEui, int fCnt, Instant at, String hex) {
		return """
				{"end_device_ids":{"device_id":"ens-03","application_ids":{"application_id":"%s"},"dev_eui":"%s"},
				 "received_at":"%s",
				 "uplink_message":{"session_key_id":"AXc3","f_port":1,"f_cnt":%d,
				   "frm_payload":"%s","received_at":"%s",
				   "rx_metadata":[{"gateway_ids":{"gateway_id":"lps8n"},"rssi":-97,"snr":6.5},
				                  {"gateway_ids":{"gateway_id":"far-away"},"rssi":-118,"snr":-9.0}]}}"""
			.formatted(app, devEui, at, fCnt, Base64.getEncoder().encodeToString(HexFormat.of().parseHex(hex)), at);
	}

	@Test
	void ttnUplinksNeedTheTokenAndTheRightApplication() throws Exception {
		String body = ttnUplink("tcc-carlos", "70B3D57ED0000001", 1, T0, "11000004D20011012C004E02C9");
		mvc.perform(post("/api/v1/integrations/ttn/uplink").contentType(MediaType.APPLICATION_JSON).content(body))
			.andExpect(status().isUnauthorized());
		mvc.perform(post("/api/v1/integrations/ttn/uplink").header("X-Webhook-Token", "ttn-token")
			.contentType(MediaType.APPLICATION_JSON)
			.content(ttnUplink("someone-else", "70B3D57ED0000001", 1, T0, "11000004D20011012C004E02C9")))
			.andExpect(status().isForbidden());
		mvc.perform(post("/api/v1/integrations/ttn/uplink").header("X-Webhook-Token", "ttn-token")
			.contentType(MediaType.APPLICATION_JSON)
			.content(ttnUplink("tcc-carlos", "0000000000000099", 1, T0, "11000004D20011012C004E02C9")))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.result", is("rejected")));
	}

	@Test
	void aTtnUplinkPlacesTheStateChangeInTimeAndCountsAcrossLostUplinks() throws Exception {
		// running for 300 s, 1234 good, 17 rejected (vector 1)
		sendUplink(ttnUplink("tcc-carlos", "70B3D57ED0000001", 1, T0, "11000004D20011012C004E02C9")).andExpect(jsonPath("$.result", is("applied")));
		// TTN retried the same uplink
		sendUplink(ttnUplink("tcc-carlos", "70B3D57ED0000001", 1, T0, "11000004D20011012C004E02C9")).andExpect(jsonPath("$.result", is("duplicate")));
		// ten minutes later, two uplinks lost on the way: fault 102 for 95 s, 70000 good (mod 65536 = 4464), 65537 rejects (= 1)
		sendUplink(ttnUplink("tcc-carlos", "70B3D57ED0000001", 4, T0.plusSeconds(600), "12006611700001005F00000190"))
			.andExpect(jsonPath("$.result", is("applied")));

		assertThat(jdbc.sql("SELECT state || ' ' || started_at FROM state_interval WHERE machine_id = 'ENS-03' ORDER BY started_at")
			.query(String.class)
			.list()).containsExactly("RUNNING " + sql(T0.minusSeconds(300)), "FAULT " + sql(T0.plusSeconds(505)));
		// 1234 -> 4464 is 3230 good; 17 -> 1 wraps to 65520, more than half the range, so it is read as a reset: 1
		assertThat(jdbc.sql("SELECT sum(good) || '/' || sum(reject) FROM production WHERE machine_id = 'ENS-03'").query(String.class).single())
			.isEqualTo("3230/1");
		assertThat(jdbc.sql("SELECT rssi || ' ' || gateway_id FROM lorawan_uplink ORDER BY received_at LIMIT 1").query(String.class).single())
			.isEqualTo("-97 lps8n");
		assertThat(jdbc.sql("SELECT avg FROM analog_sample WHERE machine_id = 'ENS-03' AND name = 'motor-temp' ORDER BY recorded_at DESC LIMIT 1")
			.query(Double.class)
			.single()).isEqualTo(40.0);
	}

	private org.springframework.test.web.servlet.ResultActions sendUplink(String body) throws Exception {
		return mvc.perform(post("/api/v1/integrations/ttn/uplink").header("X-Webhook-Token", "ttn-token")
			.contentType(MediaType.APPLICATION_JSON)
			.content(body)).andExpect(status().isOk());
	}

	private String sql(Instant instant) {
		return jdbc.sql("SELECT (:t)::timestamptz::text").param("t", java.sql.Timestamp.from(instant)).query(String.class).single();
	}

	private int intervals() {
		return jdbc.sql("SELECT count(*) FROM state_interval").query(Integer.class).single();
	}
}
