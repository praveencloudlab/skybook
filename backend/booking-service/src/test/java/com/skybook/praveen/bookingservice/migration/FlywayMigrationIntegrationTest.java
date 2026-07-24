package com.skybook.praveen.bookingservice.migration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The fresh-database migration path (SEAT_SELECTION_MODULE.md §8/§15, rounds
 * 4-7): on an empty database, Flyway must bootstrap the ENTIRE schema (V1
 * baseline) before applying the seat-selection deltas (V2 breakdown columns,
 * V3 DRAFT-admitting status CHECK, V4 uk_flight_seat removal) - and the
 * result must satisfy Hibernate's ddl-auto: validate, which this context
 * boots with. A lone V1-delta design failed exactly here: Flyway runs before
 * Hibernate, so there would have been no booking_passengers table to ALTER.
 *
 * The existing-database path (baseline-on-migrate adopting the schema at
 * version 1, then running only V2+V3+V4 with the backfill) is verified
 * against the real pre-branch compose database at deploy time - it can't be
 * faithfully simulated here without replaying a Hibernate-created schema
 * byte for byte.
 */
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class FlywayMigrationIntegrationTest {

    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        // Re-enable what src/test/resources/application.properties disables
        // for every other test: this test IS the migration test.
        registry.add("spring.flyway.enabled", () -> "true");
        // Keep the production setting: Flyway builds the schema, Hibernate
        // only validates it. If V1+V2 diverge from the entities, this
        // context fails to boot and the test fails - the strongest check.
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
    }

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void freshDatabaseGetsBaselinePlusDeltasAndSurvivesHibernateValidate() {
        // Reaching here at all means ddl-auto: validate accepted the
        // Flyway-built schema. Now prove all five migrations actually ran.
        List<Map<String, Object>> applied = jdbc.queryForList(
                "SELECT version, description, success FROM flyway_schema_history ORDER BY installed_rank");

        assertThat(applied).hasSize(5);
        for (int i = 0; i < 5; i++) {
            assertThat(applied.get(i))
                    .containsEntry("version", String.valueOf(i + 1))
                    .containsEntry("success", true);
        }
    }

    @Test
    void ownerSubjectColumnIsAddedNullable() {
        // V5 (§4.2): ownership snapshot, nullable so legacy rows stay ADMIN-only.
        String nullable = jdbc.queryForObject("""
                SELECT is_nullable FROM information_schema.columns
                WHERE table_name = 'bookings' AND column_name = 'owner_subject'""", String.class);
        assertThat(nullable).isEqualTo("YES");
    }

    @Test
    void statusCheckAdmitsDraft() {
        // V3 (§5.1a): without the replaced CHECK constraint every draft
        // insert would fail at the database regardless of the Java enum.
        String checkClause = jdbc.queryForObject("""
                SELECT pg_get_constraintdef(oid)
                FROM pg_constraint
                WHERE conname = 'bookings_booking_status_check'""", String.class);

        assertThat(checkClause).contains("DRAFT");
    }

    @Test
    void ukFlightSeatIsGone() {
        // V4 (§2.6, round 7): the unconditional unique constraint broke
        // cancel -> rebook-same-seat; live exclusivity is inventory's job.
        Integer count = jdbc.queryForObject("""
                SELECT count(*)
                FROM pg_constraint
                WHERE conname = 'uk_flight_seat'""", Integer.class);

        assertThat(count).isZero();
    }

    @Test
    void breakdownColumnsAreNotNullAndSeatNumberIsDraftNullable() {
        List<Map<String, Object>> columns = jdbc.queryForList("""
                SELECT column_name, is_nullable
                FROM information_schema.columns
                WHERE table_name = 'booking_passengers'
                  AND column_name IN ('base_fare', 'seat_surcharge',
                                      'charged_seat_assignment_mode', 'currency', 'seat_number')
                ORDER BY column_name""");

        assertThat(columns).hasSize(5);
        columns.forEach(col -> {
            String name = (String) col.get("column_name");
            String nullable = (String) col.get("is_nullable");
            if (name.equals("seat_number")) {
                assertThat(nullable).as("seat_number must be nullable for the draft stage").isEqualTo("YES");
            } else {
                assertThat(nullable).as(name + " must be NOT NULL").isEqualTo("NO");
            }
        });
    }
}
