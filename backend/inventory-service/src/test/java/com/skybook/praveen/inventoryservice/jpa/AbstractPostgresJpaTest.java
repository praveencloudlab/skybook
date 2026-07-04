package com.skybook.praveen.inventoryservice.jpa;

import com.skybook.praveen.inventoryservice.config.JpaAuditingConfig;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Base for entity/repository integration tests: real PostgreSQL via
 * Testcontainers (Docker must be running).
 *
 * The container is a singleton started once in a static initializer and
 * shared by every subclass - all subclasses use identical context
 * configuration, so Spring's context cache means one schema creation for the
 * whole JPA test suite.
 *
 * JpaAuditingConfig is imported explicitly: @DataJpaTest doesn't pick up
 * regular @Configuration classes, and without @EnableJpaAuditing every
 * insert would fail on the non-null createdAt/updatedAt columns.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(JpaAuditingConfig.class)
public abstract class AbstractPostgresJpaTest {

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
}
