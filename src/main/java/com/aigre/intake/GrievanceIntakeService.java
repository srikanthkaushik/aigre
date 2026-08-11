package com.aigre.intake;

import com.aigre.classification.ClassificationResult;
import com.aigre.classification.LlmGrievanceClassifier;
import com.aigre.sla.Priority;
import com.aigre.sla.SlaCalculator;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

/**
 * Complaint intake (portal channel only — email is a separately-scoped later
 * milestone). Writes to the systems-of-record Postgres tables; deliberately
 * distinct from the RAG corpus ingestion pipeline in com.aigre.ingestion.
 */
@Service
public class GrievanceIntakeService {

    private final NamedParameterJdbcTemplate jdbc;
    private final LlmGrievanceClassifier classifier;
    private final SlaCalculator slaCalculator;

    public GrievanceIntakeService(
            NamedParameterJdbcTemplate jdbc, LlmGrievanceClassifier classifier, SlaCalculator slaCalculator) {
        this.jdbc = jdbc;
        this.classifier = classifier;
        this.slaCalculator = slaCalculator;
    }

    public GrievanceIntakeResponse submit(GrievanceIntakeRequest request) {
        UUID citizenId = insertCitizenIfProvided(request);

        ClassificationResult classification = classifier.classify(request.rawText());
        String status = resolveStatus(classification);
        Priority priority = "TRIAGED".equals(status) ? resolvePriority(classification) : null;
        Instant submittedAt = Instant.now();
        Instant slaDueAt = priority == null ? null : slaCalculator.resolveDueAt(priority, submittedAt);

        UUID grievanceId = UUID.randomUUID();
        insertGrievance(grievanceId, citizenId, request.rawText(), classification, status, priority, slaDueAt, submittedAt);
        insertStatusHistory(grievanceId, status, submittedAt);

        return new GrievanceIntakeResponse(
                grievanceId,
                status,
                classification.department(),
                classification.category(),
                classification.confidence(),
                priority == null ? null : priority.name(),
                slaDueAt);
    }

    private String resolveStatus(ClassificationResult classification) {
        if (!classification.actionable()) {
            return "NOT_ACTIONABLE";
        }
        return classification.isConfident() ? "TRIAGED" : "NEEDS_CLARIFICATION";
    }

    /** Defensive: an LLM-emitted priority string that doesn't match the enum falls back to MEDIUM rather than crashing. */
    private Priority resolvePriority(ClassificationResult classification) {
        try {
            return Priority.valueOf(classification.priority());
        } catch (IllegalArgumentException | NullPointerException e) {
            return Priority.MEDIUM;
        }
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

    private void insertGrievance(
            UUID grievanceId,
            UUID citizenId,
            String rawText,
            ClassificationResult classification,
            String status,
            Priority priority,
            Instant slaDueAt,
            Instant submittedAt) {
        jdbc.update(
                """
                INSERT INTO grievances (id, channel, citizen_id, raw_text, department_predicted, category,
                    priority, classification_confidence, sentiment_label, sentiment_score, status, sla_due_at,
                    submitted_at)
                VALUES (:id, 'PORTAL', :citizenId, :rawText, :department, :category,
                    :priority, :confidence, :sentimentLabel, :sentimentScore, :status, :slaDueAt, :submittedAt)
                """,
                new MapSqlParameterSource()
                        .addValue("id", grievanceId)
                        .addValue("citizenId", citizenId)
                        .addValue("rawText", rawText)
                        .addValue("department", classification.department())
                        .addValue("category", classification.category())
                        .addValue("priority", priority == null ? null : priority.name())
                        .addValue("confidence", classification.confidence())
                        .addValue("sentimentLabel", classification.sentimentLabel())
                        .addValue("sentimentScore", classification.sentimentScore())
                        .addValue("status", status)
                        .addValue("slaDueAt", toTimestamp(slaDueAt))
                        .addValue("submittedAt", toTimestamp(submittedAt)));
    }

    private void insertStatusHistory(UUID grievanceId, String status, Instant changedAt) {
        jdbc.update(
                """
                INSERT INTO status_history (id, grievance_id, from_status, to_status, changed_by, changed_at)
                VALUES (:id, :grievanceId, NULL, :status, 'system:intake', :changedAt)
                """,
                new MapSqlParameterSource()
                        .addValue("id", UUID.randomUUID())
                        .addValue("grievanceId", grievanceId)
                        .addValue("status", status)
                        .addValue("changedAt", toTimestamp(changedAt)));
    }

    // pgjdbc can't infer the SQL type for a bare java.time.Instant via addValue(); java.sql.Timestamp works directly.
    private static Timestamp toTimestamp(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }
}
