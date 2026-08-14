package com.aigre.workflow;

import com.aigre.classification.ClassificationResult;
import com.aigre.classification.LlmGrievanceClassifier;
import com.aigre.duplicate.DuplicateDetectionService;
import com.aigre.sla.Priority;
import com.aigre.sla.SlaCalculator;
import org.bsc.langgraph4j.CompileConfig;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.GraphStateException;
import org.bsc.langgraph4j.StateGraph;
import org.bsc.langgraph4j.checkpoint.PostgresSaver;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import javax.sql.DataSource;
import java.sql.SQLException;
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
 * Checkpointing is Postgres-backed (PostgresSaver, reusing the app's own DataSource bean), so a
 * paused human-review workflow survives an app restart -- the graph/thread state lives in
 * lg4jthread/lg4jcheckpoint (created idempotently by PostgresSaver itself, see schema.sql), not
 * this process's heap.
 */
@Configuration
public class GrievanceWorkflowGraphConfig {

    public static final String HUMAN_REVIEW_NODE = "human_review";

    private final LlmGrievanceClassifier classifier;
    private final SlaCalculator slaCalculator;
    private final NamedParameterJdbcTemplate jdbc;
    private final DuplicateDetectionService duplicateDetectionService;
    private final DataSource dataSource;

    public GrievanceWorkflowGraphConfig(
            LlmGrievanceClassifier classifier,
            SlaCalculator slaCalculator,
            NamedParameterJdbcTemplate jdbc,
            DuplicateDetectionService duplicateDetectionService,
            DataSource dataSource) {
        this.classifier = classifier;
        this.slaCalculator = slaCalculator;
        this.jdbc = jdbc;
        this.duplicateDetectionService = duplicateDetectionService;
        this.dataSource = dataSource;
    }

    @Bean
    public CompiledGraph<GrievanceWorkflowState> grievanceWorkflowGraph() throws GraphStateException, SQLException {
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

        // .datasource(dataSource) reuses the app's own Hikari-pooled DataSource bean (the same one
        // RagConfig hands to PgVectorEmbeddingStore) -- without it, PostgresSaver falls back to
        // building its own unpooled PGSimpleDataSource, i.e. one new raw connection per checkpoint
        // read/write. .stateSerializer(graph.getStateSerializer()) reuses the exact
        // ObjectStreamStateSerializer the StateGraph constructor above already built, instead of
        // constructing a second one by hand. createTables(true) is idempotent (CREATE TABLE IF NOT
        // EXISTS) and runs on every startup, same cadence as schema.sql itself.
        PostgresSaver saver = PostgresSaver.builder()
                .datasource(dataSource)
                .stateSerializer(graph.getStateSerializer())
                .createTables(true)
                .dropTablesFirst(false)
                .build();

        return graph.compile(CompileConfig.builder()
                .checkpointSaver(saver)
                .interruptBefore(HUMAN_REVIEW_NODE)
                .build());
    }

    private Map<String, Object> classify(GrievanceWorkflowState state) {
        ClassificationResult result = classifier.classify(state.rawText());
        boolean routeToReview = result.actionable() && !result.isConfident();

        persistPredictedClassification(state.grievanceId(), result);

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
     * Writes the LLM's tentative guess to the row the moment classify() produces it, regardless
     * of whether the graph then pauses at HUMAN_REVIEW_NODE or proceeds straight to commit().
     * Without this, department_predicted stayed NULL for the entire time a low-confidence case
     * sat paused -- GrievanceQueryService.list() filters WHERE department = :department, and NULL
     * never matches, so the case was invisible in every department-scoped employee's Pending
     * Review query, and even a direct-by-ID lookup 403'd (DepartmentAccess.requireOwnDepartment
     * rejects a null grievance department for anyone but ADMIN). department_confirmed and status
     * are deliberately untouched here -- only commit() sets those, and only department_confirmed
     * once a human has actually reviewed the case, preserving the AI-guess-vs-human-confirmed
     * audit distinction commit() already relies on.
     */
    private void persistPredictedClassification(String grievanceId, ClassificationResult result) {
        Priority priority = resolvePriority(result.priority());
        jdbc.update(
                """
                UPDATE grievances
                SET department_predicted = :department,
                    category = :category,
                    priority = :priority,
                    classification_confidence = :confidence,
                    sentiment_label = :sentimentLabel,
                    sentiment_score = :sentimentScore
                WHERE id = :id
                """,
                new MapSqlParameterSource()
                        .addValue("id", UUID.fromString(grievanceId))
                        .addValue("department", result.department())
                        .addValue("category", result.category())
                        .addValue("priority", priority == null ? null : priority.name())
                        .addValue("confidence", result.confidence())
                        .addValue("sentimentLabel", result.sentimentLabel())
                        .addValue("sentimentScore", result.sentimentScore()));
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
        String departmentConfirmed = state.humanReviewed() ? state.finalDepartment().orElse(null) : null;
        String category = state.finalCategory().orElse(null);
        String effectiveDepartment =
                departmentConfirmed != null ? departmentConfirmed : state.predictedDepartment().orElse(null);

        UUID duplicateOfId = "TRIAGED".equals(status)
                ? duplicateDetectionService.findOpenDuplicate(effectiveDepartment, category, grievanceId, now).orElse(null)
                : null;
        if (duplicateOfId != null) {
            status = "DUPLICATE";
        }
        // Scenario 5: a duplicate doesn't open a second SLA clock -- only the original carries one.
        Instant slaDueAt = ("TRIAGED".equals(status) && priority != null)
                ? slaCalculator.resolveDueAt(priority, now)
                : null;
        String resolutionNotes = duplicateOfId != null
                ? "Automatically linked as a duplicate of " + duplicateOfId
                : state.reviewNote().orElse(null);

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
                    duplicate_of_id = :duplicateOfId,
                    resolution_notes = :resolutionNotes
                WHERE id = :id
                """,
                new MapSqlParameterSource()
                        .addValue("id", grievanceId)
                        .addValue("departmentPredicted", state.predictedDepartment().orElse(null))
                        .addValue("departmentConfirmed", departmentConfirmed)
                        .addValue("category", category)
                        .addValue("priority", priority == null ? null : priority.name())
                        .addValue("confidence", state.confidence())
                        .addValue("sentimentLabel", state.sentimentLabel().orElse(null))
                        .addValue("sentimentScore", state.sentimentScore())
                        .addValue("status", status)
                        .addValue("slaDueAt", toTimestamp(slaDueAt))
                        .addValue("duplicateOfId", duplicateOfId)
                        .addValue("resolutionNotes", resolutionNotes));

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
                        .addValue("note", resolutionNotes));

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
