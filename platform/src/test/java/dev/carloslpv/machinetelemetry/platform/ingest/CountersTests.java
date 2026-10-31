package dev.carloslpv.machinetelemetry.platform.ingest;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CountersTests {

	@Test
	void theFirstReadingIsOnlyABaseline() {
		assertThat(Counters.delta(null, null, 500, 32)).isNull();
	}

	@Test
	void normalIncrease() {
		assertThat(Counters.delta(100L, 32, 112, 32)).isEqualTo(12);
	}

	@Test
	void aDintThatWrappedStillCountsForward() {
		assertThat(Counters.delta(4294967290L, 32, 3, 32)).isEqualTo(9);
	}

	@Test
	void a16BitLoraCounterThatWrapped() {
		assertThat(Counters.delta(65530L, 16, 4, 16)).isEqualTo(10);
		// the gateway sends the full value; only the low 16 bits count
		assertThat(Counters.delta(70000L, 16, 70010, 16)).isEqualTo(10);
	}

	@Test
	void aCounterZeroedByTheOperatorIsAResetNotFourBillionUnits() {
		assertThat(Counters.delta(5000L, 32, 7, 32)).isEqualTo(7);
	}

	@Test
	void changingTheCounterWidthStartsOver() {
		assertThat(Counters.delta(100L, 32, 120, 16)).isNull();
	}
}
