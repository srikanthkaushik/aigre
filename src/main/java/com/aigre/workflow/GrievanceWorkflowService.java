package com.aigre.workflow;

import com.aigre.auth.CitizenTokenService;
import com.aigre.classification.ClassificationResult;
import com.aigre.classification.LlmGrievanceClassifier;
import com.aigre.intake.GrievanceIdGenerator;
import com.aigre.intake.GrievanceIntakeRequest;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.GraphInput;
import org.bsc.langgraph4j.RunnableConfig;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
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
    private final LlmGrievanceClassifier classifier;
    private final GrievanceIdGenerator grievanceIdGenerator;
    private final CitizenTokenService citizenTokenService;

    public GrievanceWorkflowService(
            NamedParameterJdbcTemplate jdbc,
            CompiledGraph<GrievanceWorkflowState> grievanceWorkflowGraph,
            LlmGrievanceClassifier classifier,
            GrievanceIdGenerator grievanceIdGenerator,
            CitizenTokenService citizenTokenService) {
        this.jdbc = jdbc;
        this.graph = grievanceWorkflowGraph;
        this.classifier = classifier;
        this.grievanceIdGenerator = grievanceIdGenerator;
        this.citizenTokenService = citizenTokenService;
    }

    public GrievanceWorkflowResponse start(GrievanceIntakeRequest request) {
        return start(request, "PORTAL");
    }

    /**
     * Channel-aware entry point -- the portal's {@link #start(GrievanceIntakeRequest)} delegates
     * here with "PORTAL"; com.aigre.email.EmailGrievancePoller calls this directly with "EMAIL" so
     * an emailed complaint runs through the identical classify/human-review/commit graph, not a
     * parallel path.
     */
    public GrievanceWorkflowResponse start(GrievanceIntakeRequest request, String channel) {
        UUID citizenId = insertCitizenIfProvided(request);
        String grievanceId = grievanceIdGenerator.next();
        Instant submittedAt = Instant.now();
        insertNewGrievance(grievanceId, citizenId, request.rawText(), submittedAt, channel);
        insertStatusHistory(grievanceId, null, "NEW", submittedAt, "system:workflow", null);

        RunnableConfig config = RunnableConfig.builder().threadId(grievanceId).build();
        graph.invoke(Map.of("grievanceId", grievanceId, "rawText", request.rawText()), config);

        return buildResponse(grievanceId, config);
    }

    public GrievanceWorkflowResponse resume(String grievanceId, GrievanceReviewDecision decision) {
        RunnableConfig config = RunnableConfig.builder().threadId(grievanceId).build();
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

    /**
     * The citizen-facing counterpart to resume(): instead of a supervisor's override decision,
     * the citizen supplies more detail about their own complaint. grievances.raw_text is never
     * mutated -- the follow-up is stored as its own grievance_clarifications row instead, so the
     * employee dashboard can render the original complaint and each follow-up as distinct,
     * timestamped entries rather than one concatenated blob. Reclassification still runs against
     * the full picture (original + every clarification so far, built in-memory), and auto-resumes
     * the paused workflow -- reusing the exact same resume mechanism the supervisor path uses,
     * just with the reclassification's own values standing in for a human's typed decision -- only
     * if the new classification is now actually confident. If it's still not, the case stays
     * paused for a supervisor; nothing is force-committed on a guess just because the citizen
     * tried once.
     *
     * Scoped out of this pass: a reclassification that flips to not-actionable after
     * clarification doesn't auto-resolve as NOT_ACTIONABLE -- the graph's "actionable" state was
     * fixed by the original classify() call and human_review's resume map doesn't currently
     * override it. Rare in practice (a citizen clarifying a vague complaint into "never mind,
     * that's not really an issue" is an edge case); left for a supervisor to close out via the
     * existing update_grievance_status tool rather than adding that plumbing now.
     */
    public GrievanceWorkflowResponse clarify(String grievanceId, String additionalText) {
        RunnableConfig config = RunnableConfig.builder().threadId(grievanceId).build();
        ensureResumable(grievanceId, config);
        if (!HUMAN_REVIEW_NODE.equals(graph.getState(config).next())) {
            throw new IllegalStateException(
                    "Grievance " + grievanceId + " is no longer awaiting review -- it may already have "
                            + "been routed or resolved.");
        }

        String originalRawText = jdbc.queryForObject(
                "SELECT raw_text FROM grievances WHERE id = :id",
                new MapSqlParameterSource("id", grievanceId),
                String.class);
        String trimmedDetail = additionalText.trim();

        StringBuilder combined = new StringBuilder(originalRawText);
        for (ClarificationEntry prior : fetchClarifications(grievanceId)) {
            combined.append("\n\nAdditional detail from citizen: ").append(prior.text());
        }
        combined.append("\n\nAdditional detail from citizen: ").append(trimmedDetail);

        ClassificationResult result = classifier.classify(combined.toString());

        jdbc.update(
                """
                INSERT INTO grievance_clarifications (id, grievance_id, additional_text, submitted_at)
                VALUES (:id, :grievanceId, :additionalText, :submittedAt)
                """,
                new MapSqlParameterSource()
                        .addValue("id", UUID.randomUUID())
                        .addValue("grievanceId", grievanceId)
                        .addValue("additionalText", trimmedDetail)
                        .addValue("submittedAt", toTimestamp(Instant.now())));

        if (result.isConfident()) {
            Map<String, Object> resumeInputs = new HashMap<>();
            resumeInputs.put("reviewedDepartment", result.department());
            resumeInputs.put("reviewedCategory", result.category());
            resumeInputs.put("reviewedPriority", result.priority());
            resumeInputs.put("reviewedConfidence", result.confidence());
            resumeInputs.put("reviewedReasoning", result.reasoning());
            resumeInputs.put("reviewNote", "Reclassified automatically after the citizen provided additional detail.");
            resumeInputs.put("reviewedBy", "system:citizen-clarification");
            graph.invoke(GraphInput.resume(resumeInputs), config);
        }
        // else: still not confident -- stays paused. The new clarification row is now visible to
        // whoever reviews it next, even though this attempt didn't resolve it.

        return buildResponse(grievanceId, config);
    }

    public GrievanceWorkflowResponse status(String grievanceId) {
        RunnableConfig config = RunnableConfig.builder().threadId(grievanceId).build();
        return buildResponse(grievanceId, config);
    }

    /**
     * resume() only makes sense on a run that actually paused in the graph. Checked upfront with
     * a clear message rather than letting GraphInput.resume() fail deeper with a less actionable
     * error -- e.g. a seeded/demo grievance or one submitted via the plain (non-workflow) intake
     * endpoint has no checkpoint at all.
     */
    private void ensureResumable(String grievanceId, RunnableConfig config) {
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
    private GrievanceWorkflowResponse buildResponse(String grievanceId, RunnableConfig config) {
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
                       classification_confidence, sla_due_at, raw_text, duplicate_of_id, citizen_id
                FROM grievances WHERE id = :id
                """,
                new MapSqlParameterSource("id", grievanceId));

        String department = row.get("department_confirmed") != null
                ? (String) row.get("department_confirmed")
                : (String) row.get("department_predicted");
        Double confidence = (Double) row.get("classification_confidence");
        Instant slaDueAt = row.get("sla_due_at") instanceof Timestamp ts ? ts.toInstant() : null;
        String duplicateOfId = (String) row.get("duplicate_of_id");
        // Silent recognition token for a returning citizen's browser -- only issued when contact
        // info was provided (citizen_id non-null); never issued for anonymous submissions, so
        // chat stays exactly as it is today for them. See CitizenTokenService's own javadoc for
        // why this isn't a "type your email to look up your grievances" flow.
        String citizenToken = row.get("citizen_id") instanceof UUID citizenId
                ? citizenTokenService.issueToken(citizenId)
                : null;

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
                (String) row.get("raw_text"),
                fetchClarifications(grievanceId),
                duplicateOfId,
                citizenToken);
    }

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

    private void insertNewGrievance(
            String grievanceId, UUID citizenId, String rawText, Instant submittedAt, String channel) {
        jdbc.update(
                """
                INSERT INTO grievances (id, channel, citizen_id, raw_text, status, submitted_at)
                VALUES (:id, :channel, :citizenId, :rawText, 'NEW', :submittedAt)
                """,
                new MapSqlParameterSource()
                        .addValue("id", grievanceId)
                        .addValue("channel", channel)
                        .addValue("citizenId", citizenId)
                        .addValue("rawText", rawText)
                        .addValue("submittedAt", toTimestamp(submittedAt)));
    }

    private void insertStatusHistory(
            String grievanceId, String fromStatus, String toStatus, Instant changedAt, String changedBy, String note) {
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
