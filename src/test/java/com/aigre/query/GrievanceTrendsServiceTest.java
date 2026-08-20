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
        insertGrievance("pothole", "HIGH", -0.5, now, now.plusSeconds(3600), now.plusSeconds(7200), "RESOLVED", null); // on-time
        insertGrievance("pothole", "HIGH", -0.2, now, now.plusSeconds(432000), now.plusSeconds(7200), "RESOLVED", null); // late
        insertGrievance("noise", "LOW", 0.3, now, null, now.minusSeconds(3600), "TRIAGED", null); // currently breached, open
        insertGrievance("pothole", "MEDIUM", null, now, null, null, "NEW", null); // no SLA clock at all
        insertGrievance("streetlight", "CRITICAL", -0.9, now, null, null, "NEW", null); // No Confidence band
        insertGrievance("graffiti", "HIGH", 0.9, now, null, null, "NEW", null); // High Confidence band
    }

    @AfterEach
    void cleanupFixtures() {
        jdbc.update("DELETE FROM grievances WHERE department_predicted = :dept", new MapSqlParameterSource("dept", DEPT));
    }

    @Test
    void aggregatesVolumeCategoryPriorityAndSlaForTheGivenDepartment() {
        TrendsResponse trends = service.trends(DEPT, 30);

        assertThat(trends.volumeByDay()).hasSize(1);
        assertThat(trends.volumeByDay().get(0).count()).isEqualTo(6);

        Map<String, Integer> categoryCounts = toMap(trends.byCategory(), CategoryCount::category, CategoryCount::count);
        assertThat(categoryCounts).containsEntry("pothole", 3).containsEntry("noise", 1)
                .containsEntry("streetlight", 1).containsEntry("graffiti", 1);

        Map<String, Integer> priorityCounts = toMap(trends.byPriority(), PriorityCount::priority, PriorityCount::count);
        assertThat(priorityCounts).containsEntry("HIGH", 3).containsEntry("LOW", 1)
                .containsEntry("MEDIUM", 1).containsEntry("CRITICAL", 1);

        // Bands: -0.9 -> No Confidence, -0.5 -> Low Confidence, -0.2 -> Neutral (band lower
        // bounds are inclusive, so -0.2 lands in NEUTRAL not LOW_CONFIDENCE), 0.3 -> Moderate
        // Confidence, 0.9 -> High Confidence. The null-sentiment fixture row is excluded entirely.
        assertThat(trends.sentimentByDay()).hasSize(1);
        DailySentimentLevels levels = trends.sentimentByDay().get(0);
        assertThat(levels.noConfidence()).isEqualTo(1);
        assertThat(levels.lowConfidence()).isEqualTo(1);
        assertThat(levels.neutral()).isEqualTo(1);
        assertThat(levels.moderateConfidence()).isEqualTo(1);
        assertThat(levels.highConfidence()).isEqualTo(1);

        assertThat(trends.slaSnapshot().resolvedOnTime()).isEqualTo(1);
        assertThat(trends.slaSnapshot().resolvedLate()).isEqualTo(1);
        assertThat(trends.slaSnapshot().currentlyBreachedOpen()).isEqualTo(1);

        assertThat(trends.recurringIssues()).isEmpty();
    }

    @Test
    void unknownDepartmentReturnsEmptyAggregates() {
        TrendsResponse trends = service.trends("NO-SUCH-DEPARTMENT", 30);

        assertThat(trends.volumeByDay()).isEmpty();
        assertThat(trends.byCategory()).isEmpty();
        assertThat(trends.slaSnapshot().resolvedOnTime()).isZero();
        assertThat(trends.slaSnapshot().resolvedLate()).isZero();
        assertThat(trends.slaSnapshot().currentlyBreachedOpen()).isZero();
        assertThat(trends.recurringIssues()).isEmpty();
    }

    @Test
    void recurringIssueSurfacedWhenThreeOrMoreReportsShareARoot() {
        Instant now = Instant.now();
        String root = insertGrievance("pothole", "MEDIUM", null, now, null, null, "TRIAGED", null);
        insertGrievance("pothole", "MEDIUM", null, now, null, null, "DUPLICATE", root);
        insertGrievance("pothole", "MEDIUM", null, now, null, null, "DUPLICATE", root);

        TrendsResponse trends = service.trends(DEPT, 30);

        assertThat(trends.recurringIssues()).hasSize(1);
        RecurringIssue issue = trends.recurringIssues().get(0);
        assertThat(issue.grievanceId()).isEqualTo(root);
        assertThat(issue.repeatCount()).isEqualTo(2);
        assertThat(issue.category()).isEqualTo("pothole");
        assertThat(issue.department()).isEqualTo(DEPT);
    }

    /** Proves the recursive walk resolves a multi-hop chain to the true root, not the intermediate. */
    @Test
    void multiHopChainResolvesToTheTrueRootNotAnIntermediateDuplicate() {
        Instant now = Instant.now();
        String a = insertGrievance("noise", "LOW", null, now, null, null, "TRIAGED", null);
        String b = insertGrievance("noise", "LOW", null, now, null, null, "DUPLICATE", a);
        insertGrievance("noise", "LOW", null, now, null, null, "DUPLICATE", b);

        TrendsResponse trends = service.trends(DEPT, 30);

        assertThat(trends.recurringIssues()).hasSize(1);
        RecurringIssue issue = trends.recurringIssues().get(0);
        assertThat(issue.grievanceId()).isEqualTo(a);
        assertThat(issue.repeatCount()).isEqualTo(2);
    }

    @Test
    void onlyTwoTotalReportsStaysBelowTheRecurringThreshold() {
        Instant now = Instant.now();
        String root = insertGrievance("graffiti", "LOW", null, now, null, null, "TRIAGED", null);
        insertGrievance("graffiti", "LOW", null, now, null, null, "DUPLICATE", root);

        TrendsResponse trends = service.trends(DEPT, 30);

        assertThat(trends.recurringIssues()).isEmpty();
    }

    private <T, K, V> Map<K, V> toMap(List<T> list, Function<T, K> keyFn, Function<T, V> valueFn) {
        return list.stream().collect(Collectors.toMap(keyFn, valueFn));
    }

    private String insertGrievance(
            String category, String priority, Double sentimentScore,
            Instant submittedAt, Instant resolvedAt, Instant slaDueAt, String status, String duplicateOfId) {
        String id = UUID.randomUUID().toString();
        jdbc.update(
                """
                INSERT INTO grievances (id, channel, raw_text, department_predicted, category, priority,
                    sentiment_score, status, sla_due_at, resolved_at, submitted_at, duplicate_of_id)
                VALUES (:id, 'PORTAL', 'fixture row', :dept, :category, :priority,
                    :sentimentScore, :status, :slaDueAt, :resolvedAt, :submittedAt, :duplicateOfId)
                """,
                new MapSqlParameterSource()
                        .addValue("id", id)
                        .addValue("dept", DEPT)
                        .addValue("category", category)
                        .addValue("priority", priority)
                        .addValue("sentimentScore", sentimentScore)
                        .addValue("status", status)
                        .addValue("slaDueAt", toTimestamp(slaDueAt))
                        .addValue("resolvedAt", toTimestamp(resolvedAt))
                        .addValue("submittedAt", toTimestamp(submittedAt))
                        .addValue("duplicateOfId", duplicateOfId));
        return id;
    }

    private static Timestamp toTimestamp(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }
}
