package com.aigre.duplicate;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Scenario 5 from plan.md §1.4: a newly (about to be) classified grievance that matches an
 * existing still-open one in the same department+category within a recent window is linked as a
 * duplicate instead of opening a second SLA clock. No structured location field exists in the
 * schema (raw_text is free text only), so "same category + location window" from the plan
 * narrows to department+category+time -- the only structured signals actually available.
 *
 * Deliberately excludes status values that mean "this issue is no longer actively open" (mirrors
 * GrievanceMcpTools.TERMINAL_STATUSES, plus DUPLICATE itself) -- a new report should never link
 * against something already resolved/closed/out-of-scope, or against another duplicate. Excluding
 * DUPLICATE-status rows from candidacy also means a freshly-created duplicate always resolves
 * directly to the true original in one hop rather than growing a chain (the next report in the
 * same window matches the original, not the duplicate, since the duplicate's own status is no
 * longer a matchable candidate).
 */
@Service
public class DuplicateDetectionService {

    private static final Set<String> NOT_MATCHABLE_STATUSES =
            Set.of("RESOLVED", "CLOSED", "NOT_ACTIONABLE", "DUPLICATE");

    private final NamedParameterJdbcTemplate jdbc;
    private final int windowDays;

    public DuplicateDetectionService(
            NamedParameterJdbcTemplate jdbc,
            @Value("${grievances.duplicate-window-days:7}") int windowDays) {
        this.jdbc = jdbc;
        this.windowDays = windowDays;
    }

    /**
     * Returns the earliest still-open grievance in the same department+category submitted within
     * the lookback window, if any. excludeGrievanceId keeps a grievance from matching itself when
     * its own row already exists at check time (the workflow path's commit() node checks after
     * start() already inserted a bare row; the plain intake path checks before insert, so the
     * exclude is a harmless no-op there since no row with that id exists yet).
     */
    public Optional<String> findOpenDuplicate(
            String department, String category, String excludeGrievanceId, Instant asOf) {
        if (department == null || category == null) {
            return Optional.empty();
        }
        Instant windowStart = asOf.minus(windowDays, ChronoUnit.DAYS);

        List<String> matches = jdbc.query(
                """
                SELECT id FROM grievances
                WHERE COALESCE(department_confirmed, department_predicted) = :department
                  AND category = :category
                  AND status NOT IN (:excludedStatuses)
                  AND submitted_at >= :windowStart
                  AND submitted_at < :asOf
                  AND id <> :excludeId
                ORDER BY submitted_at ASC
                LIMIT 1
                """,
                new MapSqlParameterSource()
                        .addValue("department", department)
                        .addValue("category", category)
                        .addValue("excludedStatuses", NOT_MATCHABLE_STATUSES)
                        .addValue("windowStart", Timestamp.from(windowStart))
                        .addValue("asOf", Timestamp.from(asOf))
                        .addValue("excludeId", excludeGrievanceId),
                (rs, rowNum) -> rs.getString("id"));

        return matches.isEmpty() ? Optional.empty() : Optional.of(matches.get(0));
    }
}
