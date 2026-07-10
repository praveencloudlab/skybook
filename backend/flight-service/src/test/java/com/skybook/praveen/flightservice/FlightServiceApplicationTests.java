package com.skybook.praveen.flightservice;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Context-load smoke test against a Testcontainers PostgreSQL, same pattern
 * as inventory-service's AbstractPostgresSpringBootTest / payment-service's
 * AbstractPaymentIntegrationTest. Previously a plain @SpringBootTest that
 * inherited application.yml's localhost:5432 datasource - which only ever
 * passed on a machine with a local Postgres already running (true of the
 * original dev machine, where a native PostgreSQL service made this
 * dependency invisible), and failed on the first CI run where no such
 * service exists. Kafka is not needed: flight-service has no Kafka
 * dependency at all.
 */
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class FlightServiceApplicationTests {

	static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

	static {
		POSTGRES.start();
	}

	@DynamicPropertySource
	static void datasourceProperties(DynamicPropertyRegistry registry) {
		registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
		registry.add("spring.datasource.username", POSTGRES::getUsername);
		registry.add("spring.datasource.password", POSTGRES::getPassword);
		registry.add("spring.jpa.hibernate.ddl-auto", () -> "create-drop");
	}

	@Test
	void contextLoads() {
	}

}
