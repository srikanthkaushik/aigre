package com.aigre.workflow;

import com.aigre.intake.GrievanceIntakeRequest;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.GraphInput;
import org.bsc.langgraph4j.RunnableConfig;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static com.aigre.workflow.GrievanceWorkflowGraphConfig.HUMAN_REVIEW_NODE;

/**
 * Orchestrates the milestone-4 agent workflow: creates the grievance row (status NEW), runs it
 * through the classify/human_review/commit graph, and exposes the pause/resume cycle over HTTP.
 * The graph is threaded per grievance -- threadId = grievanceId -- so getState/resume always
 * target the right paused run (CLAUDE.md gotcha: passing a plain Map to invoke() restarts from
 * START instead of resuming; GraphInput.resume(map) with the same threadId is required).
 */
@Service
public class GrievanceWorkflowService {

    private final NamedParameterJdbcTemplate jdbc;
    private final CompiledGraph<GrievanceWorkflowState> graph;

    public GrievanceWorkflowService(NamedParameterJdbcTemplate jdbc, CompiledGraph<GrievanceWorkflowState> grievanceWorkflowGraph) {
        this.jdbc = jdbc;
        this.graph = grievanceWorkflowGraph;
    }

    public GrievanceWorkflowResponse start(GrievanceIntakeRequest request) {
        UUID citizenId = insertCitizenIfProvided(request);
        UUID grievanceId = UUID.randomUUID();
        Instant submittedAt = Instant.now();
        insertNewGrievance(grievanceId, citizenId, request.rawText(), submittedAt);
        insertStatusHistory(grievanceId, null, "NEW", submittedAt, "system:workflow", null);

        RunnableConfig config = RunnableConfig.builder().threadId(grievanceId.toString()).build();
        graph.invoke(Map.of("grievanceId", grievanceId.toString(), "rawText", request.rawText()), config);

        return buildResponse(grievanceId, config);
    }

    public GrievanceWorkflowResponse resume(UUID grievanceId, GrievanceReviewDecision decision) {
        RunnableConfig config = RunnableConfig.builder().threadId(grievanceId.toString()).build();
        ensureResumable(grievanceId, config);

        Map<String, Object> resumeInputs = new HashMap<>();
        if (decision.department() != null) {
            resumeInputs.put("reviewedDepartment", decision.department());
        }
        if (decision.category() != null) {
            resumeInputs.put("reviewedCategory", decision.category());
        }
        if (decision.priority() != null) {
            resumeInputs.put("reviewedPriority", decision.priority());
        }
        resumeInputs.put("reviewNote", decision.note());
        resumeInputs.put("reviewedBy", decision.reviewedBy());

        graph.invoke(GraphInput.resume(resumeInputs), config);

        return buildResponse(grievanceId, config);
    }

    public GrievanceWorkflowResponse status(UUID grievanceId) {
        RunnableConfig config = RunnableConfig.builder().threadId(grievanceId.toString()).build();
        return buildResponse(grievanceId, config);
    }

    /**
     * resume() only makes sense on a run that actually paused in the graph. Checked upfront with
     * a clear message rather than letting GraphInput.resume() fail deeper with a less actionable
     * error -- e.g. a seeded/demo grievance or one submitted via the plain (non-workflow) intake
     * endpoint has no checkpoint at all.
     */
    private void ensureResumable(UUID grievanceId, RunnableConfig config) {
        try {
            graph.getState(config);
        } catch (IllegalStateException e) {
            throw new IllegalStateException(
                    "Grievance " + grievanceId + " was never routed through the review workflow "
                            + "(e.g. a seeded/demo record, or one submitted via the plain intake endpoint) "
                            + "and has no paused run to resume.");
        }
    }

    /**
     * Always re-derives from the checkpoint's next() (not invoke()'s return value) whether the
     * run is paused -- CLAUDE.md gotcha: InterruptionMetadata isn't a NodeOutput, so invoke()'s
     * Optional<State> doesn't reliably distinguish pause from completion on its own.
     */
    private GrievanceWorkflowResponse buildResponse(UUID grievanceId, RunnableConfig config) {
        boolean pendingReview = false;
        String reasoning = null;
        try {
            var snapshot = graph.getState(config);
            pendingReview = HUMAN_REVIEW_NODE.equals(snapshot.next());
            reasoning = snapshot.state().reasoning().orElse(null);
        } catch (IllegalStateException e) {
            // No checkpoint for this threadId -- this grievance never went through the workflow
            // graph (e.g. a seeded operational row, or one submitted via the plain intake
            // endpoint). Not an error: it's simply not part of an active/paused workflow run.
        }

        Map<String, Object> row = jdbc.queryForMap(
                """
                SELECT status, department_predicted, department_confirmed, category, priority,
                       classification_confidence, sla_due_at, raw_text
                FROM grievances WHERE id = :id
                """,
                new MapSqlParameterSource("id", grievanceId));

        String department = row.get("department_confirmed") != null
                ? (String) row.get("department_confirmed")
                : (String) row.get("department_predicted");
        Double confidence = (Double) row.get("classification_confidence");
        Instant slaDueAt = row.get("sla_due_at") instanceof Timestamp ts ? ts.toInstant() : null;

        return new GrievanceWorkflowResponse(
                grievanceId,
                (String) row.get("status"),
                pendingReview,
                department,
                (String) row.get("category"),
                (String) row.get("priority"),
                confidence == null ? -1.0 : confidence,
                slaDueAt,
                reasoning,
                (String) row.get("raw_text"));
    }

    private UUID insertCitizenIfProvided(GrievanceIntakeRequest request) {
        if (request.citizenEmail() == null && request.citizenPhone() == null) {
            return null;
        }
        UUID citizenId = UUID.randomUUID();
        jdbc.update(
                """
                INSERT INTO citizens (id, name, email, phone, preferred_contact)
                VALUES (:id, :name, :email, :phone, :preferredContact)
                """,
                new MapSqlParameterSource()
                        .addValue("id", citizenId)
                        .addValue("name", request.citizenName())
                        .addValue("email", request.citizenEmail())
                        .addValue("phone", request.citizenPhone())
                        .addValue("preferredContact", request.citizenEmail() != null ? "EMAIL" : "PHONE"));
        return citizenId;
    }

    private void insertNewGrievance(UUID grievanceId, UUID citizenId, String rawText, Instant submittedAt) {
        jdbc.update(
                """
                INSERT INTO grievances (id, channel, citizen_id, raw_text, status, submitted_at)
                VALUES (:id, 'PORTAL', :citizenId, :rawText, 'NEW', :submittedAt)
                """,
                new MapSqlParameterSource()
                        .addValue("id", grievanceId)
                        .addValue("citizenId", citizenId)
                        .addValue("rawText", rawText)
                        .addValue("submittedAt", toTimestamp(submittedAt)));
    }

    private void insertStatusHistory(
            UUID grievanceId, String fromStatus, String toStatus, Instant changedAt, String changedBy, String note) {
        jdbc.update(
                """
                INSERT INTO status_history (id, grievance_id, from_status, to_status, changed_by, changed_at, note)
                VALUES (:id, :grievanceId, :fromStatus, :toStatus, :changedBy, :changedAt, :note)
                """,
                new MapSqlParameterSource()
                        .addValue("id", UUID.randomUUID())
                        .addValue("grievanceId", grievanceId)
                        .addValue("fromStatus", fromStatus)
                        .addValue("toStatus", toStatus)
                        .addValue("changedBy", changedBy)
                        .addValue("changedAt", toTimestamp(changedAt))
                        .addValue("note", note));
    }

    private static Timestamp toTimestamp(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }
}
