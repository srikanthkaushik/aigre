package com.aigre.query;

import com.aigre.tools.GrievanceMcpTools;
import com.aigre.tools.GrievanceStatusResult;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

/**
 * Read-side queries backing the frontend (milestone 5) -- deliberately separate from
 * GrievanceMcpTools (MCP-only tool surface) even though getStatus() delegates into it, since a
 * plain-Java dashboard listing query has no reason to be exposed as an agent tool.
 */
@Service
public class GrievanceQueryService {

    private final GrievanceMcpTools mcpTools;
    private final NamedParameterJdbcTemplate jdbc;

    public GrievanceQueryService(GrievanceMcpTools mcpTools, NamedParameterJdbcTemplate jdbc) {
        this.mcpTools = mcpTools;
        this.jdbc = jdbc;
    }

    /**
     * Works for any grievance regardless of which intake path created it (plain POST
     * /grievances, the workflow graph, or a seeded operational row) -- all three write to the
     * same grievances table this ultimately queries.
     */
    public GrievanceStatusResult getStatus(String grievanceId) {
        return mcpTools.getGrievanceStatus(grievanceId);
    }

    public List<GrievanceSummary> list(String department, String status) {
        StringBuilder sql = new StringBuilder(
                """
                SELECT id, status, department_predicted, department_confirmed, category, priority,
                       classification_confidence, sla_due_at, submitted_at, resolution_notes,
                       (sla_due_at IS NOT NULL AND sla_due_at < now()
                            AND status NOT IN ('RESOLVED','CLOSED','NOT_ACTIONABLE')) AS breached
                FROM grievances
                WHERE 1=1
                """);
        MapSqlParameterSource params = new MapSqlParameterSource();
        if (department != null && !department.isBlank()) {
            sql.append(" AND COALESCE(department_confirmed, department_predicted) = :department");
            params.addValue("department", department);
        }
        if (status != null && !status.isBlank()) {
            sql.append(" AND status = :status");
            params.addValue("status", status);
        }
        sql.append(" ORDER BY submitted_at DESC LIMIT 200");

        return jdbc.query(sql.toString(), params, (rs, rowNum) -> new GrievanceSummary(
                rs.getString("id"),
                rs.getString("status"),
                rs.getString("department_confirmed") != null
                        ? rs.getString("department_confirmed")
                        : rs.getString("department_predicted"),
                rs.getString("category"),
                rs.getString("priority"),
                rs.getObject("classification_confidence", Double.class),
                toInstant(rs.getTimestamp("sla_due_at")),
                toInstant(rs.getTimestamp("submitted_at")),
                rs.getString("resolution_notes"),
                rs.getBoolean("breached")));
    }

    private static Instant toInstant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
