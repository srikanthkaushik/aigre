package com.aigre.workflow;

import com.aigre.classification.ClassificationResult;
import com.aigre.classification.LlmGrievanceClassifier;
import com.aigre.sla.Priority;
import com.aigre.sla.SlaCalculator;
import org.bsc.langgraph4j.CompileConfig;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.GraphStateException;
import org.bsc.langgraph4j.StateGraph;
import org.bsc.langgraph4j.checkpoint.MemorySaver;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.bsc.langgraph4j.StateGraph.END;
import static org.bsc.langgraph4j.StateGraph.START;
import static org.bsc.langgraph4j.action.AsyncEdgeAction.edge_async;
import static org.bsc.langgraph4j.action.AsyncNodeAction.node_async;

/**
 * Milestone 4: agent workflow with a human approval gate. classify -> (confident/not-actionable:
 * commit | low-confidence: human_review, an interruptBefore pause point) -> commit -> END.
 *
 * Scenario 2 (multi-department ambiguity) is deliberately not a separate branch here -- per
 * LlmGrievanceClassifier's own documented design, department ambiguity is signaled via lower
 * confidence on its single best-guess department rather than a second department field, so it
 * already falls into the same low-confidence branch as scenario 4.
 *
 * Checkpointing uses an in-memory MemorySaver: fine for this milestone's pause/resume
 * demonstration within a single running instance; a Postgres-backed saver is an open item if
 * paused workflows ever need to survive an app restart (PROJECT.md).
 */
@Configuration
public class GrievanceWorkflowGraphConfig {

    public static final String HUMAN_REVIEW_NODE = "human_review";

    private final LlmGrievanceClassifier classifier;
    private final SlaCalculator slaCalculator;
    private final NamedParameterJdbcTemplate jdbc;

    public GrievanceWorkflowGraphConfig(
            LlmGrievanceClassifier classifier, SlaCalculator slaCalculator, NamedParameterJdbcTemplate jdbc) {
        this.classifier = classifier;
        this.slaCalculator = slaCalculator;
        this.jdbc = jdbc;
    }

    @Bean
    public CompiledGraph<GrievanceWorkflowState> grievanceWorkflowGraph() throws GraphStateException {
        StateGraph<GrievanceWorkflowState> graph = new StateGraph<>(GrievanceWorkflowState::new)
                .addNode("classify", node_async(this::classify))
                .addNode(HUMAN_REVIEW_NODE, node_async(this::humanReview))
                .addNode("commit", node_async(this::commit))
                .addEdge(START, "classify")
                .addConditionalEdges(
                        "classify",
                        edge_async(state -> state.route().orElse("commit")),
                        Map.of(HUMAN_REVIEW_NODE, HUMAN_REVIEW_NODE, "commit", "commit"))
                .addEdge(HUMAN_REVIEW_NODE, "commit")
                .addEdge("commit", END);

        return graph.compile(CompileConfig.builder()
                .checkpointSaver(new MemorySaver())
                .interruptBefore(HUMAN_REVIEW_NODE)
                .build());
    }

    private Map<String, Object> classify(GrievanceWorkflowState state) {
        ClassificationResult result = classifier.classify(state.rawText());
        boolean routeToReview = result.actionable() && !result.isConfident();

        Map<String, Object> update = new HashMap<>();
        update.put("predictedDepartment", result.department());
        update.put("finalDepartment", result.department());
        update.put("finalCategory", result.category());
        update.put("finalPriority", result.priority());
        update.put("confidence", result.confidence());
        update.put("sentimentLabel", result.sentimentLabel());
        update.put("sentimentScore", result.sentimentScore());
        update.put("actionable", result.actionable());
        update.put("reasoning", result.reasoning());
        update.put("route", routeToReview ? HUMAN_REVIEW_NODE : "commit");
        return update;
    }

    /**
     * Runs only after a resume: interruptBefore(HUMAN_REVIEW_NODE) pauses execution right here,
     * and the supervisor's decision arrives merged into state via GraphInput.resume(map) before
     * this node body executes.
     */
    private Map<String, Object> humanReview(GrievanceWorkflowState state) {
        Map<String, Object> update = new HashMap<>();
        update.put("humanReviewed", true);
        state.reviewedDepartment().ifPresent(d -> update.put("finalDepartment", d));
        state.reviewedCategory().ifPresent(c -> update.put("finalCategory", c));
        state.reviewedPriority().ifPresent(p -> update.put("finalPriority", p));
        // Only ever present on the citizen-clarification auto-resume path -- a supervisor's
        // decision doesn't re-type a confidence score or reasoning, so these stay as the
        // original classify() output on that path, same as before.
        state.reviewedConfidence().ifPresent(c -> update.put("confidence", c));
        state.reviewedReasoning().ifPresent(r -> update.put("reasoning", r));
        return update;
    }

    private Map<String, Object> commit(GrievanceWorkflowState state) {
        UUID grievanceId = UUID.fromString(state.grievanceId());
        String status = state.actionable() ? "TRIAGED" : "NOT_ACTIONABLE";
        Priority priority = resolvePriority(state.finalPriority().orElse(null));
        Instant now = Instant.now();
        Instant slaDueAt = ("TRIAGED".equals(status) && priority != null)
                ? slaCalculator.resolveDueAt(priority, now)
                : null;
        String departmentConfirmed = state.humanReviewed() ? state.finalDepartment().orElse(null) : null;

        String previousStatus = jdbc.queryForObject(
                "SELECT status FROM grievances WHERE id = :id",
                new MapSqlParameterSource("id", grievanceId),
                String.class);

        jdbc.update(
                """
                UPDATE grievances
                SET department_predicted = :departmentPredicted,
                    department_confirmed = :departmentConfirmed,
                    category = :category,
                    priority = :priority,
                    classification_confidence = :confidence,
                    sentiment_label = :sentimentLabel,
                    sentiment_score = :sentimentScore,
                    status = :status,
                    sla_due_at = :slaDueAt,
                    resolution_notes = :resolutionNotes
                WHERE id = :id
                """,
                new MapSqlParameterSource()
                        .addValue("id", grievanceId)
                        .addValue("departmentPredicted", state.predictedDepartment().orElse(null))
                        .addValue("departmentConfirmed", departmentConfirmed)
                        .addValue("category", state.finalCategory().orElse(null))
                        .addValue("priority", priority == null ? null : priority.name())
                        .addValue("confidence", state.confidence())
                        .addValue("sentimentLabel", state.sentimentLabel().orElse(null))
                        .addValue("sentimentScore", state.sentimentScore())
                        .addValue("status", status)
                        .addValue("slaDueAt", toTimestamp(slaDueAt))
                        .addValue("resolutionNotes", state.reviewNote().orElse(null)));

        jdbc.update(
                """
                INSERT INTO status_history (id, grievance_id, from_status, to_status, changed_by, changed_at, note)
                VALUES (:id, :grievanceId, :fromStatus, :toStatus, :changedBy, :changedAt, :note)
                """,
                new MapSqlParameterSource()
                        .addValue("id", UUID.randomUUID())
                        .addValue("grievanceId", grievanceId)
                        .addValue("fromStatus", previousStatus)
                        .addValue("toStatus", status)
                        .addValue("changedBy", state.humanReviewed() ? state.reviewedBy().orElse("supervisor") : "system:workflow")
                        .addValue("changedAt", toTimestamp(now))
                        .addValue("note", state.reviewNote().orElse(null)));

        Map<String, Object> update = new HashMap<>();
        update.put("committedStatus", status);
        return update;
    }

    /** Defensive: an unrecognized priority string falls back to MEDIUM rather than crashing the commit node. */
    private static Priority resolvePriority(String priority) {
        if (priority == null) {
            return null;
        }
        try {
            return Priority.valueOf(priority);
        } catch (IllegalArgumentException e) {
            return Priority.MEDIUM;
        }
    }

    private static Timestamp toTimestamp(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }
}
