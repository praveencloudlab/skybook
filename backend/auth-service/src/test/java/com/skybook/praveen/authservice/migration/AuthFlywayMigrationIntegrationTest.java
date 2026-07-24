package com.skybook.praveen.authservice.migration;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The fresh-database auth migration path (SECURITY_HARDENING_MODULE.md §4.3/§15):
 * on an empty database Flyway must build the whole schema (V1) then apply the
 * role/email-normalization delta (V2). Drives Flyway directly against a
 * throwaway Postgres - no Spring/JPA/Kafka context needed. The existing-database
 * path (idempotent V1 no-op + V2 backfill) is verified live against the real
 * compose database at deploy time.
 */
@Testcontainers(disabledWithoutDocker = true)
class AuthFlywayMigrationIntegrationTest {

    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @BeforeAll
    static void migrate() {
        POSTGRES.start();
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();
    }

    private Object query(String sql) throws Exception {
        try (Connection c = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            return rs.next() ? rs.getObject(1) : null;
        }
    }

    @Test
    void freshDatabaseAppliedV1AndV2() throws Exception {
        assertThat(query("SELECT count(*) FROM flyway_schema_history WHERE version IN ('1','2') AND success"))
                .isEqualTo(2L);
    }

    @Test
    void roleColumnIsNotNullWithACheckConstraint() throws Exception {
        assertThat(query("""
                SELECT is_nullable FROM information_schema.columns
                WHERE table_name = 'users' AND column_name = 'role'""")).isEqualTo("NO");
        assertThat(query("SELECT count(*) FROM pg_constraint WHERE conname = 'users_role_check'"))
                .isEqualTo(1L);
    }

    @Test
    void emailNormalizationIsEnforcedByAConstraint() throws Exception {
        assertThat(query("SELECT count(*) FROM pg_constraint WHERE conname = 'users_email_normalized_check'"))
                .isEqualTo(1L);
    }
}
