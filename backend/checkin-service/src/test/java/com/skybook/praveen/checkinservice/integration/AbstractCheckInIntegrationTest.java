package com.skybook.praveen.checkinservice.integration;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Full-application integration base: real PostgreSQL AND real Kafka, same
 * shape as payment-service's AbstractPaymentIntegrationTest. Scope note
 * (design doc section 17): unlike payment-service, this module's facade
 * makes real synchronous Feign calls to flight-service/inventory-service
 * for its orchestrated operations (checkIn/board/changeSeat) - those aren't
 * part of this container set, so integration tests built on this base stay
 * within the paths that don't need them: the BookingEvent consumer's
 * pure-DB CONFIRMED/CANCELLED handling, and the read-only REST endpoints.
 * booking-service (which has the identical Feign dependency shape) has no
 * full-stack integration test at all for the same reason; this scopes down
 * rather than omitting the layer entirely.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers(disabledWithoutDocker = true)
public abstract class AbstractCheckInIntegrationTest {

    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");
    static final KafkaContainer KAFKA = new KafkaContainer(DockerImageName.parse("apache/kafka:3.8.0"));

    static {
        POSTGRES.start();
        KAFKA.start();
    }

    @DynamicPropertySource
    static void containerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "create-drop");
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
    }
}
