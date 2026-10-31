package dev.carloslpv.machinetelemetry.platform;

import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;

/** PostgreSQL 18 in a container, shared by the integration tests. */
@TestConfiguration(proxyBeanMethods = false)
public class TestDatabase {

	@Bean
	@ServiceConnection
	PostgreSQLContainer postgres() {
		return new PostgreSQLContainer(DockerImageName.parse("postgres:18"));
	}
}
