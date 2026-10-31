package dev.carloslpv.machinetelemetry.gateway.outbox;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.carloslpv.machinetelemetry.gateway.outbox.Outbox.Kind;

import static org.assertj.core.api.Assertions.assertThat;

class OutboxTests {

	@TempDir
	Path dir;

	@Test
	void keepsMessagesAcrossARestartInOrder() throws Exception {
		Path file = dir.resolve("outbox.db");
		try (Outbox outbox = new Outbox(file, 100)) {
			outbox.add(Kind.STATE, "t/1", "{\"a\":1}");
			outbox.add(Kind.TELEMETRY, "t/2", "{\"a\":2}");
		}
		try (Outbox outbox = new Outbox(file, 100)) {
			assertThat(outbox.size()).isEqualTo(2);
			assertThat(outbox.peek(10)).extracting(Outbox.Entry::topic).containsExactly("t/1", "t/2");
		}
	}

	@Test
	void sequenceNumbersAreNeverReusedEvenAfterTheQueueEmpties() throws Exception {
		Path file = dir.resolve("outbox.db");
		long last;
		try (Outbox outbox = new Outbox(file, 100)) {
			long seq = outbox.add(Kind.STATE, "t", "{}");
			outbox.remove(seq);
			last = outbox.add(Kind.STATE, "t", "{}");
			outbox.remove(last);
			assertThat(outbox.size()).isZero();
		}
		try (Outbox outbox = new Outbox(file, 100)) {
			// the platform deduplicates on gateway + seq: a reused number would silently drop a message
			assertThat(outbox.add(Kind.STATE, "t", "{}")).isGreaterThan(last);
		}
	}

	@Test
	void whenFullItDropsTheOldestTelemetryBeforeAnyStateChange() throws Exception {
		try (Outbox outbox = new Outbox(dir.resolve("outbox.db"), 3)) {
			outbox.add(Kind.STATE, "state-1", "{}");
			outbox.add(Kind.TELEMETRY, "telemetry-1", "{}");
			outbox.add(Kind.TELEMETRY, "telemetry-2", "{}");
			outbox.add(Kind.STATE, "state-2", "{}");
			outbox.add(Kind.STATE, "state-3", "{}");
			assertThat(outbox.peek(10)).extracting(Outbox.Entry::topic).containsExactly("state-1", "state-2", "state-3");
			assertThat(outbox.dropped()).isEqualTo(2);
			// with no telemetry left, the oldest state goes
			outbox.add(Kind.STATE, "state-4", "{}");
			assertThat(outbox.peek(10)).extracting(Outbox.Entry::topic).containsExactly("state-2", "state-3", "state-4");
		}
	}
}
