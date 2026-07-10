package com.skybook.praveen.bookingservice;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Context-load smoke test against a Testcontainers PostgreSQL, same pattern
 * as inventory-service's AbstractPostgresSpringBootTest. Previously a plain
 * @SpringBootTest that inherited application.yml's localhost:5432 datasource -
 * which only ever passed on a machine with a local Postgres already running,
 * and failed on the first CI run where no such service exists. Kafka is
 * deliberately not provisioned: the PaymentEventConsumer's listener container
 * starts and retries in the background without blocking context startup
 * (verified empirically - this test passed locally with no broker listening
 * on 9092 at all), so a broker would only add container-startup time here.
 */
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class BookingServiceApplicationTests {

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
