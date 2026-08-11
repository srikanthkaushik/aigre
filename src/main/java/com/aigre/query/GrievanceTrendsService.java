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

        List<DailySentiment> sentimentByDay = jdbc.query(
                """
                SELECT date_trunc('day', submitted_at)::date AS day, avg(sentiment_score) AS avg_sentiment
                FROM grievances
                WHERE submitted_at >= now() - (:days || ' days')::interval AND sentiment_score IS NOT NULL
                """ + deptClause + """

                GROUP BY day ORDER BY day
                """,
                params,
                (rs, rowNum) -> new DailySentiment(rs.getDate("day").toLocalDate(), rs.getDouble("avg_sentiment")));

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

        return new TrendsResponse(volumeByDay, byCategory, byPriority, sentimentByDay, slaSnapshot);
    }
}
