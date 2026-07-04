package com.skybook.praveen.inventoryservice.integration;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Base for full-application integration tests: boots the entire Spring
 * context against a Testcontainers PostgreSQL. Kafka is NOT required -
 * inventory-service has no listeners and the producer only connects on
 * first send, which these tests never trigger (they call services, not
 * the facade).
 *
 * Same singleton-container + identical-config approach as
 * AbstractPostgresJpaTest, so all subclasses share one Spring context.
 */
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
public abstract class AbstractPostgresSpringBootTest {

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
        // Keep the expiry job from sweeping mid-test.
        registry.add("inventory.hold.sweep-interval-ms", () -> "600000");
    }
}
