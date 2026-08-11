package com.aigre.duplicate;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Deterministic against a real Postgres instance -- inserts throwaway fixture rows per test
 * rather than reusing the shared seed.sql edge cases, since duplicate-matching needs fine control
 * over department/category/status/submitted_at combinations. Each test generates its own random
 * category string rather than a realistic one like "road-surface" -- the real seeded demo data
 * (test-data/sql/seed.sql) also has real DOT/road-surface-style rows within the default 7-day
 * window, and a hardcoded realistic category collided with them on the first run (a good sign the
 * detection logic actually works against real data, but the wrong thing for an isolated test).
 */
@SpringBootTest
class DuplicateDetectionServiceTest {

    @Autowired
    private DuplicateDetectionService service;

    @Autowired
    private NamedParameterJdbcTemplate jdbc;

    @Test
    void findsAnOpenGrievanceInTheSameDepartmentAndCategory() {
        String category = uniqueCategory();
        Instant now = Instant.now();
        UUID original = insertGrievance("DOT", category, "TRIAGED", now.minus(1, ChronoUnit.DAYS));

        var match = service.findOpenDuplicate("DOT", category, UUID.randomUUID(), now);

        assertThat(match).contains(original);
    }

    @Test
    void doesNotMatchADifferentCategory() {
        Instant now = Instant.now();
        insertGrievance("DOT", uniqueCategory(), "TRIAGED", now.minus(1, ChronoUnit.DAYS));

        var match = service.findOpenDuplicate("DOT", uniqueCategory(), UUID.randomUUID(), now);

        assertThat(match).isEmpty();
    }

    @Test
    void doesNotMatchOutsideTheLookbackWindow() {
        String category = uniqueCategory();
        Instant now = Instant.now();
        insertGrievance("DOT", category, "TRIAGED", now.minus(30, ChronoUnit.DAYS));

        var match = service.findOpenDuplicate("DOT", category, UUID.randomUUID(), now);

        assertThat(match)
                .as("31-day-old submission is well outside the default 7-day window")
                .isEmpty();
    }

    @Test
    void doesNotMatchTerminalOrAlreadyDuplicateStatuses() {
        String category = uniqueCategory();
        Instant now = Instant.now();
        insertGrievance("DOT", category, "RESOLVED", now.minus(1, ChronoUnit.DAYS));
        insertGrievance("DOT", category, "CLOSED", now.minus(1, ChronoUnit.DAYS));
        insertGrievance("DOT", category, "NOT_ACTIONABLE", now.minus(1, ChronoUnit.DAYS));
        insertGrievance("DOT", category, "DUPLICATE", now.minus(1, ChronoUnit.DAYS));

        var match = service.findOpenDuplicate("DOT", category, UUID.randomUUID(), now);

        assertThat(match)
                .as("none of these are still-open issues worth linking a new report against")
                .isEmpty();
    }

    @Test
    void excludesItsOwnRowWhenGivenAsTheExcludeId() {
        String category = uniqueCategory();
        Instant now = Instant.now();
        UUID self = insertGrievance("DOT", category, "NEW", now.minus(1, ChronoUnit.HOURS));

        var match = service.findOpenDuplicate("DOT", category, self, now);

        assertThat(match)
                .as("the only candidate in scope is the grievance's own already-inserted row")
                .isEmpty();
    }

    @Test
    void picksTheEarliestMatchingCandidateWhenSeveralExist() {
        String category = uniqueCategory();
        Instant now = Instant.now();
        UUID earlier = insertGrievance("DOT", category, "TRIAGED", now.minus(3, ChronoUnit.DAYS));
        insertGrievance("DOT", category, "TRIAGED", now.minus(1, ChronoUnit.DAYS));

        var match = service.findOpenDuplicate("DOT", category, UUID.randomUUID(), now);

        assertThat(match).contains(earlier);
    }

    private static String uniqueCategory() {
        return "test-cat-" + UUID.randomUUID();
    }

    private UUID insertGrievance(String department, String category, String status, Instant submittedAt) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                """
                INSERT INTO grievances (id, channel, raw_text, department_predicted, department_confirmed,
                    category, status, submitted_at)
                VALUES (:id, 'PORTAL', 'duplicate-detection test fixture', :department, :department, :category,
                    :status, :submittedAt)
                """,
                new MapSqlParameterSource()
                        .addValue("id", id)
                        .addValue("department", department)
                        .addValue("category", category)
                        .addValue("status", status)
                        .addValue("submittedAt", Timestamp.from(submittedAt)));
        return id;
    }
}
