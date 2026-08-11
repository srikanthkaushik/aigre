package com.aigre.intake;

import com.aigre.classification.ClassificationResult;
import com.aigre.classification.LlmGrievanceClassifier;
import com.aigre.duplicate.DuplicateDetectionService;
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
    private final DuplicateDetectionService duplicateDetectionService;

    public GrievanceIntakeService(
            NamedParameterJdbcTemplate jdbc,
            LlmGrievanceClassifier classifier,
            SlaCalculator slaCalculator,
            DuplicateDetectionService duplicateDetectionService) {
        this.jdbc = jdbc;
        this.classifier = classifier;
        this.slaCalculator = slaCalculator;
        this.duplicateDetectionService = duplicateDetectionService;
    }

    public GrievanceIntakeResponse submit(GrievanceIntakeRequest request) {
        UUID citizenId = insertCitizenIfProvided(request);

        ClassificationResult classification = classifier.classify(request.rawText());
        String status = resolveStatus(classification);
        Priority priority = "TRIAGED".equals(status) ? resolvePriority(classification) : null;
        Instant submittedAt = Instant.now();
        UUID grievanceId = UUID.randomUUID();

        UUID duplicateOfId = "TRIAGED".equals(status)
                ? duplicateDetectionService
                        .findOpenDuplicate(classification.department(), classification.category(), grievanceId, submittedAt)
                        .orElse(null)
                : null;
        if (duplicateOfId != null) {
            status = "DUPLICATE";
        }
        // Scenario 5: a duplicate doesn't open a second SLA clock -- only the original carries one.
        Instant slaDueAt = (priority == null || duplicateOfId != null)
                ? null
                : slaCalculator.resolveDueAt(priority, submittedAt);

        insertGrievance(
                grievanceId, citizenId, request.rawText(), classification, status, priority, slaDueAt, submittedAt,
                duplicateOfId);
        insertStatusHistory(grievanceId, status, submittedAt, duplicateOfId);

        return new GrievanceIntakeResponse(
                grievanceId,
                status,
                classification.department(),
                classification.category(),
                classification.confidence(),
                priority == null ? null : priority.name(),
                slaDueAt,
                duplicateOfId);
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
            Instant submittedAt,
            UUID duplicateOfId) {
        jdbc.update(
                """
                INSERT INTO grievances (id, channel, citizen_id, raw_text, department_predicted, category,
                    priority, classification_confidence, sentiment_label, sentiment_score, status, sla_due_at,
                    duplicate_of_id, submitted_at)
                VALUES (:id, 'PORTAL', :citizenId, :rawText, :department, :category,
                    :priority, :confidence, :sentimentLabel, :sentimentScore, :status, :slaDueAt,
                    :duplicateOfId, :submittedAt)
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
                        .addValue("duplicateOfId", duplicateOfId)
                        .addValue("submittedAt", toTimestamp(submittedAt)));
    }

    private void insertStatusHistory(UUID grievanceId, String status, Instant changedAt, UUID duplicateOfId) {
        String note = duplicateOfId == null ? null : "Automatically linked as a duplicate of " + duplicateOfId;
        jdbc.update(
                """
                INSERT INTO status_history (id, grievance_id, from_status, to_status, changed_by, changed_at, note)
                VALUES (:id, :grievanceId, NULL, :status, 'system:intake', :changedAt, :note)
                """,
                new MapSqlParameterSource()
                        .addValue("id", UUID.randomUUID())
                        .addValue("grievanceId", grievanceId)
                        .addValue("status", status)
                        .addValue("changedAt", toTimestamp(changedAt))
                        .addValue("note", note));
    }

    // pgjdbc can't infer the SQL type for a bare java.time.Instant via addValue(); java.sql.Timestamp works directly.
    private static Timestamp toTimestamp(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }
}
