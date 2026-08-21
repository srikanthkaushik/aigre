package com.aigre.tools;

import com.aigre.sla.Priority;
import com.aigre.sla.SlaCalculator;
import com.aigre.workflow.ClarificationEntry;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * MCP tools over the grievance systems-of-record (plan milestone 3), exposed via Spring AI's
 * MCP server ({@code spring-ai-starter-mcp-server-webflux}) for a future agent (milestone 4) to
 * call. Deliberately exercised against the 5 edge cases seeded in test-data/sql/seed.sql (bad
 * department code, stale unescalated breach, 2-hop duplicate chain, anonymous no-contact
 * citizen, never-classified row) -- see GrievanceMcpToolsTest.
 *
 * Error messages are written to teach the caller what to do next (plan's "tool design is
 * engineering" rule), not just to report failure.
 */
@Component
public class GrievanceMcpTools {

    private static final Set<String> VALID_STATUSES = Set.of(
            "NEW", "NEEDS_CLARIFICATION", "TRIAGED", "ROUTED", "IN_PROGRESS", "RESOLVED", "CLOSED", "ESCALATED",
            "REOPENED", "NOT_ACTIONABLE", "DUPLICATE");

    private static final Set<String> TERMINAL_STATUSES = Set.of("RESOLVED", "CLOSED", "NOT_ACTIONABLE");

    private static final Pattern GRIEVANCE_ID_PATTERN = Pattern.compile("^G\\d{4,}$");

    private final NamedParameterJdbcTemplate jdbc;
    private final SlaCalculator slaCalculator;

    public GrievanceMcpTools(NamedParameterJdbcTemplate jdbc, SlaCalculator slaCalculator) {
        this.jdbc = jdbc;
        this.slaCalculator = slaCalculator;
    }

    @McpTool(
            name = "get_grievance_status",
            description = "Look up the current status, classification, and SLA due date for a grievance by its ID.")
    public GrievanceStatusResult getGrievanceStatus(
            @McpToolParam(description = "The grievance's ID, e.g. G0001", required = true) String grievanceId) {
        String id = parseId(grievanceId);
        List<GrievanceStatusResult> rows = jdbc.query(
                """
                SELECT g.id, g.status, g.department_predicted, g.department_confirmed, g.category, g.priority,
                       g.classification_confidence, g.sentiment_label, g.sla_due_at, g.submitted_at,
                       g.resolved_at, g.resolution_notes, g.duplicate_of_id, g.raw_text,
                       (d.id IS NOT NULL) AS department_valid,
                       (g.citizen_id IS NOT NULL AND (c.email IS NOT NULL OR c.phone IS NOT NULL)) AS citizen_contact_available
                FROM grievances g
                LEFT JOIN departments d ON d.id = g.department_predicted
                LEFT JOIN citizens c ON c.id = g.citizen_id
                WHERE g.id = :id
                """,
                new MapSqlParameterSource("id", id),
                (rs, rowNum) -> new GrievanceStatusResult(
                        rs.getString("id"),
                        rs.getString("status"),
                        rs.getString("department_predicted"),
                        rs.getString("department_confirmed"),
                        rs.getBoolean("department_valid"),
                        rs.getString("category"),
                        rs.getString("priority"),
                        rs.getObject("classification_confidence", Double.class),
                        rs.getString("sentiment_label"),
                        toInstant(rs.getTimestamp("sla_due_at")),
                        toInstant(rs.getTimestamp("submitted_at")),
                        toInstant(rs.getTimestamp("resolved_at")),
                        rs.getString("resolution_notes"),
                        rs.getBoolean("citizen_contact_available"),
                        rs.getString("duplicate_of_id"),
                        rs.getString("raw_text"),
                        fetchClarifications(id)));
        return requireFound(rows, grievanceId);
    }

    /** Mirrors GrievanceWorkflowService.fetchClarifications() -- same query, own copy per this class's own DTO. */
    private List<ClarificationEntry> fetchClarifications(String grievanceId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT additional_text, submitted_at FROM grievance_clarifications "
                        + "WHERE grievance_id = :id ORDER BY submitted_at ASC",
                new MapSqlParameterSource("id", grievanceId));

        List<ClarificationEntry> entries = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            entries.add(new ClarificationEntry(
                    (String) row.get("additional_text"),
                    ((Timestamp) row.get("submitted_at")).toInstant()));
        }
        return entries;
    }

    @McpTool(
            name = "check_sla_status",
            description = "Check whether a grievance's SLA is breached, and how many hours remain or how many "
                    + "hours overdue it is.")
    public SlaStatusResult checkSlaStatus(
            @McpToolParam(description = "The grievance's ID, e.g. G0001", required = true) String grievanceId) {
        String id = parseId(grievanceId);
        List<SlaStatusResult> rows = jdbc.query(
                """
                SELECT id, status, priority, sla_due_at,
                       (sla_due_at IS NOT NULL AND sla_due_at < now() AND status NOT IN ('RESOLVED','CLOSED','NOT_ACTIONABLE')) AS breached
                FROM grievances WHERE id = :id
                """,
                new MapSqlParameterSource("id", id),
                (rs, rowNum) -> {
                    Instant slaDueAt = toInstant(rs.getTimestamp("sla_due_at"));
                    Long hours = slaDueAt == null ? null : Duration.between(Instant.now(), slaDueAt).toHours();
                    return new SlaStatusResult(
                            rs.getString("id"), rs.getString("status"), rs.getString("priority"), slaDueAt,
                            rs.getBoolean("breached"), hours);
                });
        return requireFound(rows, grievanceId);
    }

    @McpTool(
            name = "find_duplicate_chain",
            description = "Walk a grievance's duplicate-of chain to find the true original report, even if the "
                    + "chain is several hops deep.")
    public DuplicateChainResult findDuplicateChain(
            @McpToolParam(description = "The grievance's ID, e.g. G0001", required = true) String grievanceId) {
        String id = parseId(grievanceId);
        List<Object[]> hops = jdbc.query(
                """
                WITH RECURSIVE chain AS (
                    SELECT id, duplicate_of_id, 0 AS hop FROM grievances WHERE id = :id
                    UNION ALL
                    SELECT g.id, g.duplicate_of_id, c.hop + 1
                    FROM grievances g
                    JOIN chain c ON g.id = c.duplicate_of_id
                    WHERE c.hop < 20
                )
                SELECT id, hop FROM chain ORDER BY hop
                """,
                new MapSqlParameterSource("id", id),
                (rs, rowNum) -> new Object[] {rs.getString("id"), rs.getInt("hop")});
        if (hops.isEmpty()) {
            throw notFound(grievanceId);
        }
        List<String> chain = new ArrayList<>();
        for (Object[] hop : hops) {
            chain.add((String) hop[0]);
        }
        String trueOriginal = chain.get(chain.size() - 1);
        return new DuplicateChainResult(grievanceId, trueOriginal, chain.size() - 1, chain);
    }

    @McpTool(
            name = "update_grievance_status",
            description = "Update a grievance's status and record the change in its audit history. Valid "
                    + "statuses: NEW, NEEDS_CLARIFICATION, TRIAGED, ROUTED, IN_PROGRESS, RESOLVED, CLOSED, "
                    + "ESCALATED, REOPENED, NOT_ACTIONABLE, DUPLICATE.")
    public UpdateStatusResult updateGrievanceStatus(
            @McpToolParam(description = "The grievance's ID, e.g. G0001", required = true) String grievanceId,
            @McpToolParam(description = "The new status", required = true) String newStatus,
            @McpToolParam(description = "Why the status is changing", required = true) String note,
            @McpToolParam(description = "Who is making this change (employee ID or 'system:<source>')", required = true)
                    String changedBy) {
        String id = parseId(grievanceId);
        String normalizedStatus = newStatus == null ? "" : newStatus.trim().toUpperCase();
        if (!VALID_STATUSES.contains(normalizedStatus)) {
            return new UpdateStatusResult(
                    grievanceId, null, newStatus, false,
                    "Invalid status '" + newStatus + "'. Valid values: " + String.join(", ", VALID_STATUSES));
        }

        List<String> currentStatusRows = jdbc.query(
                "SELECT status FROM grievances WHERE id = :id",
                new MapSqlParameterSource("id", id),
                (rs, rowNum) -> rs.getString("status"));
        if (currentStatusRows.isEmpty()) {
            throw notFound(grievanceId);
        }
        String previousStatus = currentStatusRows.get(0);
        Instant now = Instant.now();

        jdbc.update(
                """
                UPDATE grievances
                SET status = :newStatus,
                    resolution_notes = :note,
                    resolved_at = CASE WHEN :newStatus IN (:terminalStatuses) THEN :now ELSE resolved_at END
                WHERE id = :id
                """,
                new MapSqlParameterSource()
                        .addValue("id", id)
                        .addValue("newStatus", normalizedStatus)
                        .addValue("note", note)
                        .addValue("terminalStatuses", TERMINAL_STATUSES)
                        .addValue("now", Timestamp.from(now)));

        jdbc.update(
                """
                INSERT INTO status_history (id, grievance_id, from_status, to_status, changed_by, changed_at, note)
                VALUES (:historyId, :grievanceId, :fromStatus, :toStatus, :changedBy, :changedAt, :note)
                """,
                new MapSqlParameterSource()
                        .addValue("historyId", UUID.randomUUID())
                        .addValue("grievanceId", id)
                        .addValue("fromStatus", previousStatus)
                        .addValue("toStatus", normalizedStatus)
                        .addValue("changedBy", changedBy)
                        .addValue("changedAt", Timestamp.from(now))
                        .addValue("note", note));

        return new UpdateStatusResult(grievanceId, previousStatus, normalizedStatus, true, "Updated.");
    }

    @McpTool(
            name = "reopen_grievance",
            description = "Reopen a CLOSED grievance (plan.md scenario 7): bumps its priority one tier, clears "
                    + "its resolution, recomputes a fresh SLA due date, and routes it back to its confirmed "
                    + "department for another look. Only works on grievances currently in CLOSED status.")
    public ReopenResult reopenGrievance(
            @McpToolParam(description = "The grievance's ID, e.g. G0001", required = true) String grievanceId,
            @McpToolParam(description = "Why the grievance is being reopened", required = true) String reason,
            @McpToolParam(
                            description = "Who is reopening it (citizen ID/contact, or 'system:<source>')",
                            required = true)
                    String reopenedBy) {
        String id = parseId(grievanceId);
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT status, priority FROM grievances WHERE id = :id", new MapSqlParameterSource("id", id));
        if (rows.isEmpty()) {
            throw notFound(grievanceId);
        }
        String currentStatus = (String) rows.get(0).get("status");
        if (!"CLOSED".equals(currentStatus)) {
            return new ReopenResult(
                    grievanceId, currentStatus, null, null, null, null, false,
                    "Only CLOSED grievances can be reopened; this grievance is currently " + currentStatus + ".");
        }

        String currentPriorityRaw = (String) rows.get(0).get("priority");
        Priority currentPriority = currentPriorityRaw == null ? Priority.MEDIUM : Priority.valueOf(currentPriorityRaw);
        Priority bumpedPriority = currentPriority.oneTierUp();
        Instant now = Instant.now();
        Instant newSlaDueAt = slaCalculator.resolveDueAt(bumpedPriority, now);

        jdbc.update(
                """
                UPDATE grievances
                SET status = 'REOPENED',
                    priority = :priority,
                    resolved_at = NULL,
                    sla_due_at = :slaDueAt,
                    resolution_notes = :note
                WHERE id = :id
                """,
                new MapSqlParameterSource()
                        .addValue("id", id)
                        .addValue("priority", bumpedPriority.name())
                        .addValue("slaDueAt", Timestamp.from(newSlaDueAt))
                        .addValue("note", reason));

        jdbc.update(
                """
                INSERT INTO status_history (id, grievance_id, from_status, to_status, changed_by, changed_at, note)
                VALUES (:id, :grievanceId, 'CLOSED', 'REOPENED', :changedBy, :changedAt, :note)
                """,
                new MapSqlParameterSource()
                        .addValue("id", UUID.randomUUID())
                        .addValue("grievanceId", id)
                        .addValue("changedBy", reopenedBy)
                        .addValue("changedAt", Timestamp.from(now))
                        .addValue("note", reason));

        return new ReopenResult(
                grievanceId, "CLOSED", "REOPENED", currentPriority.name(), bumpedPriority.name(), newSlaDueAt, true,
                "Reopened.");
    }

    private String parseId(String rawId) {
        if (rawId == null || !GRIEVANCE_ID_PATTERN.matcher(rawId).matches()) {
            throw new IllegalArgumentException(
                    "'" + rawId + "' is not a valid grievance ID -- expected a format like G0001.");
        }
        return rawId;
    }

    private <T> T requireFound(List<T> rows, String grievanceId) {
        if (rows.isEmpty()) {
            throw notFound(grievanceId);
        }
        return rows.get(0);
    }

    private IllegalArgumentException notFound(String grievanceId) {
        return new IllegalArgumentException(
                "No grievance found with ID " + grievanceId + ". Double-check the ID, or use a search/list tool "
                        + "to find the correct one.");
    }

    private static Instant toInstant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
