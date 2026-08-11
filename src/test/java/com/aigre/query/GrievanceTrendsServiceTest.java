package com.aigre.query;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Fixture rows use a dedicated department code ("ZZTEST") that can never collide with real data
 * (departments are deliberately not an FK -- see schema.sql), so aggregates can be asserted
 * exactly against this isolated slice rather than fragile-against-shared-state totals. Cleaned up
 * in @AfterEach -- this is a live shared Postgres instance, not an isolated test DB (the
 * RetrievalEvalTest lesson: don't leave destructive/polluting state behind for other tests or the
 * running app to trip over).
 */
@SpringBootTest
class GrievanceTrendsServiceTest {

    private static final String DEPT = "ZZTEST";

    @Autowired
    private GrievanceTrendsService service;

    @Autowired
    private NamedParameterJdbcTemplate jdbc;

    @BeforeEach
    void seedFixtures() {
        Instant now = Instant.now();
        insertGrievance("pothole", "HIGH", -0.5, now, now.plusSeconds(3600), now.plusSeconds(7200), "RESOLVED"); // on-time
        insertGrievance("pothole", "HIGH", -0.2, now, now.plusSeconds(432000), now.plusSeconds(7200), "RESOLVED"); // late
        insertGrievance("noise", "LOW", 0.3, now, null, now.minusSeconds(3600), "TRIAGED"); // currently breached, open
        insertGrievance("pothole", "MEDIUM", null, now, null, null, "NEW"); // no SLA clock at all
    }

    @AfterEach
    void cleanupFixtures() {
        jdbc.update("DELETE FROM grievances WHERE department_predicted = :dept", new MapSqlParameterSource("dept", DEPT));
    }

    @Test
    void aggregatesVolumeCategoryPriorityAndSlaForTheGivenDepartment() {
        TrendsResponse trends = service.trends(DEPT, 30);

        assertThat(trends.volumeByDay()).hasSize(1);
        assertThat(trends.volumeByDay().get(0).count()).isEqualTo(4);

        Map<String, Integer> categoryCounts = toMap(trends.byCategory(), CategoryCount::category, CategoryCount::count);
        assertThat(categoryCounts).containsEntry("pothole", 3).containsEntry("noise", 1);

        Map<String, Integer> priorityCounts = toMap(trends.byPriority(), PriorityCount::priority, PriorityCount::count);
        assertThat(priorityCounts).containsEntry("HIGH", 2).containsEntry("LOW", 1).containsEntry("MEDIUM", 1);

        assertThat(trends.sentimentByDay()).hasSize(1);
        assertThat(trends.sentimentByDay().get(0).avgSentiment()).isCloseTo(-0.1333, org.assertj.core.data.Offset.offset(0.001));

        assertThat(trends.slaSnapshot().resolvedOnTime()).isEqualTo(1);
        assertThat(trends.slaSnapshot().resolvedLate()).isEqualTo(1);
        assertThat(trends.slaSnapshot().currentlyBreachedOpen()).isEqualTo(1);
    }

    @Test
    void unknownDepartmentReturnsEmptyAggregates() {
        TrendsResponse trends = service.trends("NO-SUCH-DEPARTMENT", 30);

        assertThat(trends.volumeByDay()).isEmpty();
        assertThat(trends.byCategory()).isEmpty();
        assertThat(trends.slaSnapshot().resolvedOnTime()).isZero();
        assertThat(trends.slaSnapshot().resolvedLate()).isZero();
        assertThat(trends.slaSnapshot().currentlyBreachedOpen()).isZero();
    }

    private <T, K, V> Map<K, V> toMap(List<T> list, Function<T, K> keyFn, Function<T, V> valueFn) {
        return list.stream().collect(Collectors.toMap(keyFn, valueFn));
    }

    private void insertGrievance(
            String category, String priority, Double sentimentScore,
            Instant submittedAt, Instant resolvedAt, Instant slaDueAt, String status) {
        jdbc.update(
                """
                INSERT INTO grievances (id, channel, raw_text, department_predicted, category, priority,
                    sentiment_score, status, sla_due_at, resolved_at, submitted_at)
                VALUES (:id, 'PORTAL', 'fixture row', :dept, :category, :priority,
                    :sentimentScore, :status, :slaDueAt, :resolvedAt, :submittedAt)
                """,
                new MapSqlParameterSource()
                        .addValue("id", UUID.randomUUID())
                        .addValue("dept", DEPT)
                        .addValue("category", category)
                        .addValue("priority", priority)
                        .addValue("sentimentScore", sentimentScore)
                        .addValue("status", status)
                        .addValue("slaDueAt", toTimestamp(slaDueAt))
                        .addValue("resolvedAt", toTimestamp(resolvedAt))
                        .addValue("submittedAt", toTimestamp(submittedAt)));
    }

    private static Timestamp toTimestamp(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }
}
