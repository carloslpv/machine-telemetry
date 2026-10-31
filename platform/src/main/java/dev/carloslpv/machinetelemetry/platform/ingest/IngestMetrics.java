package dev.carloslpv.machinetelemetry.platform.ingest;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

import org.springframework.stereotype.Component;

/** {@code mt_ingest_messages_total{type, result}} on /actuator/prometheus. */
@Component
public class IngestMetrics {

	private final MeterRegistry registry;

	IngestMetrics(MeterRegistry registry) {
		this.registry = registry;
	}

	public void count(String type, IngestService.Result result) {
		Counter.builder("mt.ingest.messages")
			.description("Messages received from gateways and LoRaWAN devices, by outcome")
			.tag("type", type)
			.tag("result", result.name().toLowerCase())
			.register(registry)
			.increment();
	}
}
