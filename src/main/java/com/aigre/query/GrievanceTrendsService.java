package com.aigre.query;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Read-only aggregation queries backing the employee dashboard's Trends tab. Five focused
 * queries rather than one mega-query -- each is cheap at this data scale and stays independently
 * readable; a FILTER-clause single-row query is used only for the SLA snapshot, where the three
 * counts are genuinely one shape.
 *
 * department == null/blank means "all departments" -- not a magic sentinel value, the WHERE
 * clause is built conditionally, same pattern GrievanceQueryService.list() already uses.
 */
@Service
public class GrievanceTrendsService {

    private final NamedParameterJdbcTemplate jdbc;

    public GrievanceTrendsService(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public TrendsResponse trends(String department, int days) {
        MapSqlParameterSource params = new MapSqlParameterSource().addValue("days", days);
        String deptClause = "";
        if (department != null && !department.isBlank()) {
            deptClause = " AND COALESCE(department_confirmed, department_predicted) = :department";
            params.addValue("department", department);
        }

        List<DailyCount> volumeByDay = jdbc.query(
                """
                SELECT date_trunc('day', submitted_at)::date AS day, count(*) AS cnt
                FROM grievances
                WHERE submitted_at >= now() - (:days || ' days')::interval
                """ + deptClause + """

                GROUP BY day ORDER BY day
                """,
                params,
                (rs, rowNum) -> new DailyCount(rs.getDate("day").toLocalDate(), rs.getInt("cnt")));

        List<CategoryCount> byCategory = jdbc.query(
                """
                SELECT category, count(*) AS cnt
                FROM grievances
                WHERE submitted_at >= now() - (:days || ' days')::interval AND category IS NOT NULL
                """ + deptClause + """

                GROUP BY category ORDER BY cnt DESC LIMIT 8
                """,
                params,
                (rs, rowNum) -> new CategoryCount(rs.getString("category"), rs.getInt("cnt")));

        List<PriorityCount> byPriority = jdbc.query(
                """
                SELECT priority, count(*) AS cnt
                FROM grievances
                WHERE submitted_at >= now() - (:days || ' days')::interval AND priority IS NOT NULL
                """ + deptClause + """

                GROUP BY priority ORDER BY priority
                """,
                params,
                (rs, rowNum) -> new PriorityCount(rs.getString("priority"), rs.getInt("cnt")));

        params.addValue("b1", SentimentLevel.LOW_CONFIDENCE.lowerBound);
        params.addValue("b2", SentimentLevel.NEUTRAL.lowerBound);
        params.addValue("b3", SentimentLevel.MODERATE_CONFIDENCE.lowerBound);
        params.addValue("b4", SentimentLevel.HIGH_CONFIDENCE.lowerBound);

        List<DailySentimentLevels> sentimentByDay = jdbc.query(
                """
                SELECT date_trunc('day', submitted_at)::date AS day,
                    count(*) FILTER (WHERE sentiment_score < :b1) AS no_confidence,
                    count(*) FILTER (WHERE sentiment_score >= :b1 AND sentiment_score < :b2) AS low_confidence,
                    count(*) FILTER (WHERE sentiment_score >= :b2 AND sentiment_score < :b3) AS neutral,
                    count(*) FILTER (WHERE sentiment_score >= :b3 AND sentiment_score < :b4) AS moderate_confidence,
                    count(*) FILTER (WHERE sentiment_score >= :b4) AS high_confidence
                FROM grievances
                WHERE submitted_at >= now() - (:days || ' days')::interval AND sentiment_score IS NOT NULL
                """ + deptClause + """

                GROUP BY day ORDER BY day
                """,
                params,
                (rs, rowNum) -> new DailySentimentLevels(
                        rs.getDate("day").toLocalDate(),
                        rs.getInt("no_confidence"),
                        rs.getInt("low_confidence"),
                        rs.getInt("neutral"),
                        rs.getInt("moderate_confidence"),
                        rs.getInt("high_confidence")));

        SlaSnapshot slaSnapshot = jdbc.queryForObject(
                """
                SELECT
                    count(*) FILTER (WHERE resolved_at IS NOT NULL AND resolved_at <= sla_due_at) AS on_time,
                    count(*) FILTER (WHERE resolved_at IS NOT NULL AND resolved_at > sla_due_at) AS late,
                    count(*) FILTER (WHERE resolved_at IS NULL AND sla_due_at IS NOT NULL
                        AND sla_due_at < now() AND status NOT IN ('RESOLVED','CLOSED','NOT_ACTIONABLE')) AS breached_open
                FROM grievances
                WHERE submitted_at >= now() - (:days || ' days')::interval
                """ + deptClause,
                params,
                (rs, rowNum) -> new SlaSnapshot(rs.getInt("on_time"), rs.getInt("late"), rs.getInt("breached_open")));

        // Resolves every duplicate_of_id chain to its true root (a recursive walk-forward, same
        // 20-hop-capped pattern as GrievanceMcpTools.findDuplicateChain, just for every row at
        // once instead of one id at a time) and surfaces roots with 3+ total reports (root + 2
        // repeats) -- "the same issue reported again" using a signal that already exists, not a
        // new one. Seeded from the whole table (unfiltered) so a chain resolves correctly
        // regardless of an individual duplicate's own submission date; the days/department filter
        // only applies to the root's own columns in the final SELECT.
        String deptClauseAliased = "";
        if (department != null && !department.isBlank()) {
            deptClauseAliased = " AND COALESCE(o.department_confirmed, o.department_predicted) = :department";
        }

        List<RecurringIssue> recurringIssues = jdbc.query(
                """
                WITH RECURSIVE chain AS (
                    SELECT id AS origin_id, id AS current_id, duplicate_of_id, 0 AS hop
                    FROM grievances
                    UNION ALL
                    SELECT c.origin_id, g.id, g.duplicate_of_id, c.hop + 1
                    FROM chain c
                    JOIN grievances g ON g.id = c.duplicate_of_id
                    WHERE c.hop < 20
                ),
                roots AS (
                    SELECT DISTINCT ON (origin_id) origin_id, current_id AS root_id
                    FROM chain
                    ORDER BY origin_id, hop DESC
                )
                SELECT o.id, o.category, COALESCE(o.department_confirmed, o.department_predicted) AS department,
                       left(o.raw_text, 140) AS snippet, o.submitted_at,
                       count(*) FILTER (WHERE r.origin_id <> r.root_id) AS repeat_count
                FROM roots r
                JOIN grievances o ON o.id = r.root_id
                WHERE o.submitted_at >= now() - (:days || ' days')::interval
                """ + deptClauseAliased + """

                GROUP BY o.id, o.category, department, o.raw_text, o.submitted_at
                HAVING count(*) FILTER (WHERE r.origin_id <> r.root_id) >= 2
                ORDER BY repeat_count DESC
                LIMIT 10
                """,
                params,
                (rs, rowNum) -> new RecurringIssue(
                        rs.getString("id"),
                        rs.getString("department"),
                        rs.getString("category"),
                        rs.getString("snippet"),
                        rs.getTimestamp("submitted_at").toInstant(),
                        rs.getInt("repeat_count")));

        return new TrendsResponse(volumeByDay, byCategory, byPriority, sentimentByDay, slaSnapshot, recurringIssues);
    }
}
