package com.aigre.tools;

import com.aigre.intake.GrievanceIdGenerator;
import com.aigre.workflow.ClarificationEntry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Calls GrievanceMcpTools directly (not through the MCP wire protocol) against the 5 deliberate
 * edge cases seeded in test-data/sql/seed.sql -- this is the higher-signal test for the tools'
 * actual logic; a separate live protocol-level probe confirms the MCP server wiring itself.
 *
 * Requires seed.sql to have been run against aigre-pg -- these are fixed G####-format IDs, not
 * generated per-test-run.
 */
@SpringBootTest
class GrievanceMcpToolsTest {

    private static final String BAD_DEPARTMENT_CODE_ID = "G0011";
    private static final String STALE_BREACH_ID = "G0012";
    private static final String DUPLICATE_CHAIN_ORIGINAL_ID = "G0013";
    private static final String DUPLICATE_CHAIN_TAIL_ID = "G0015";
    private static final String ANONYMOUS_NO_CONTACT_ID = "G0016";
    private static final String NEVER_CLASSIFIED_ID = "G0017";

    @Autowired
    private GrievanceMcpTools tools;

    @Autowired
    private NamedParameterJdbcTemplate jdbc;

    @Autowired
    private GrievanceIdGenerator grievanceIdGenerator;

    @Test
    void badDepartmentCodeIsSurfacedNotHidden() {
        GrievanceStatusResult result = tools.getGrievanceStatus(BAD_DEPARTMENT_CODE_ID);

        assertThat(result.departmentPredicted()).isEqualTo("ZZLEGACY");
        assertThat(result.departmentValid())
                .as("ZZLEGACY is not a real department -- the tool should flag this, not silently accept it")
                .isFalse();
        assertThat(result.rawText())
                .isEqualTo("Legacy complaint about a vehicle registration issue, filed before the department was renamed.");
        assertThat(result.clarifications())
                .as("no follow-up detail was ever submitted for this seeded row")
                .isEmpty();
    }

    @Test
    void statusIncludesClarificationsInSubmittedOrder() {
        String id = insertClosedFixture("MEDIUM");
        insertClarification(id, "the pothole is right in front of 12 Elm St", Instant.now().minus(Duration.ofDays(2)));
        insertClarification(id, "it's gotten worse after last night's rain", Instant.now().minus(Duration.ofDays(1)));

        GrievanceStatusResult result = tools.getGrievanceStatus(id);

        assertThat(result.clarifications())
                .extracting(ClarificationEntry::text)
                .containsExactly(
                        "the pothole is right in front of 12 Elm St",
                        "it's gotten worse after last night's rain");
    }

    @Test
    void staleUnescalatedBreachIsDetected() {
        SlaStatusResult result = tools.checkSlaStatus(STALE_BREACH_ID);

        assertThat(result.status()).isEqualTo("IN_PROGRESS");
        assertThat(result.breached()).isTrue();
        assertThat(result.hoursUntilOrPastDue()).isNegative();
    }

    @Test
    void duplicateChainResolvesTwoHopsToTrueOriginal() {
        DuplicateChainResult result = tools.findDuplicateChain(DUPLICATE_CHAIN_TAIL_ID);

        assertThat(result.trueOriginalId())
                .as("must resolve through the full chain, not stop at the first hop")
                .isEqualTo(DUPLICATE_CHAIN_ORIGINAL_ID);
        assertThat(result.hopsToOriginal()).isEqualTo(2);
        assertThat(result.chain()).containsExactly(
                DUPLICATE_CHAIN_TAIL_ID, "G0014", DUPLICATE_CHAIN_ORIGINAL_ID);
    }

    @Test
    void anonymousGrievanceHasNoContactInfoAvailable() {
        GrievanceStatusResult result = tools.getGrievanceStatus(ANONYMOUS_NO_CONTACT_ID);

        assertThat(result.citizenContactAvailable())
                .as("no citizen record at all -- cannot notify")
                .isFalse();
    }

    @Test
    void neverClassifiedGrievanceReturnsNullsGracefully() {
        GrievanceStatusResult result = tools.getGrievanceStatus(NEVER_CLASSIFIED_ID);

        assertThat(result.status()).isEqualTo("NEW");
        assertThat(result.departmentPredicted()).isNull();
        assertThat(result.classificationConfidence()).isNull();
    }

    @Test
    void unknownGrievanceIdProducesAClearError() {
        assertThatThrownBy(() -> tools.getGrievanceStatus("G99999999"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("No grievance found");
    }

    @Test
    void malformedGrievanceIdProducesAClearError() {
        assertThatThrownBy(() -> tools.getGrievanceStatus("not-a-grievance-id"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not a valid grievance ID");
    }

    @Test
    void updateStatusRejectsUnknownStatusWithoutWriting() {
        UpdateStatusResult result = tools.updateGrievanceStatus(
                BAD_DEPARTMENT_CODE_ID, "BOGUS_STATUS", "test", "test-harness");

        assertThat(result.success()).isFalse();
        assertThat(result.message()).contains("Invalid status");
    }

    @Test
    void updateStatusRoundTripsThroughAuditHistory() {
        GrievanceStatusResult before = tools.getGrievanceStatus(STALE_BREACH_ID);

        UpdateStatusResult result = tools.updateGrievanceStatus(
                STALE_BREACH_ID, "ESCALATED", "escalated during MCP tools testing", "test-harness");

        assertThat(result.success()).isTrue();
        assertThat(result.previousStatus()).isEqualTo(before.status());
        assertThat(result.newStatus()).isEqualTo("ESCALATED");

        GrievanceStatusResult after = tools.getGrievanceStatus(STALE_BREACH_ID);
        assertThat(after.status()).isEqualTo("ESCALATED");

        // Restore original status so this test is repeatable against the same seeded row.
        tools.updateGrievanceStatus(STALE_BREACH_ID, before.status(), "test cleanup", "test-harness");
    }

    @Test
    void reopenRejectsAGrievanceThatIsNotClosed() {
        ReopenResult result = tools.reopenGrievance(STALE_BREACH_ID, "wants another look", "test-harness");

        assertThat(result.success()).isFalse();
        assertThat(result.message()).contains("CLOSED").contains("IN_PROGRESS");
    }

    @Test
    void reopenBumpsPriorityClearsResolutionAndRecomputesSla() {
        String id = insertClosedFixture("MEDIUM");

        ReopenResult result = tools.reopenGrievance(id, "issue recurred", "citizen-contact");

        assertThat(result.success()).isTrue();
        assertThat(result.previousStatus()).isEqualTo("CLOSED");
        assertThat(result.newStatus()).isEqualTo("REOPENED");
        assertThat(result.previousPriority()).isEqualTo("MEDIUM");
        assertThat(result.newPriority()).isEqualTo("HIGH");
        assertThat(result.newSlaDueAt()).isNotNull();

        GrievanceStatusResult after = tools.getGrievanceStatus(id);
        assertThat(after.status()).isEqualTo("REOPENED");
        assertThat(after.priority()).isEqualTo("HIGH");
        assertThat(after.resolvedAt())
                .as("reopening must clear the old resolution -- update_grievance_status never nulls this")
                .isNull();
        assertThat(after.resolutionNotes()).isEqualTo("issue recurred");
    }

    @Test
    void reopenAtCriticalPriorityStaysCriticalInsteadOfCrashing() {
        String id = insertClosedFixture("CRITICAL");

        ReopenResult result = tools.reopenGrievance(id, "still broken", "citizen-contact");

        assertThat(result.previousPriority()).isEqualTo("CRITICAL");
        assertThat(result.newPriority()).isEqualTo("CRITICAL");
    }

    private String insertClosedFixture(String priority) {
        String id = grievanceIdGenerator.next();
        jdbc.update(
                """
                INSERT INTO grievances (id, channel, raw_text, department_predicted, department_confirmed,
                    category, priority, status, resolved_at, resolution_notes, submitted_at)
                VALUES (:id, 'PORTAL', 'reopen-test fixture', 'DOT', 'DOT', 'road-surface',
                    :priority, 'CLOSED', now() - interval '5 days', 'closed originally', now() - interval '20 days')
                """,
                new MapSqlParameterSource().addValue("id", id).addValue("priority", priority));
        return id;
    }

    private void insertClarification(String grievanceId, String text, Instant submittedAt) {
        jdbc.update(
                "INSERT INTO grievance_clarifications (id, grievance_id, additional_text, submitted_at) "
                        + "VALUES (:id, :grievanceId, :text, :submittedAt)",
                new MapSqlParameterSource()
                        .addValue("id", UUID.randomUUID())
                        .addValue("grievanceId", grievanceId)
                        .addValue("text", text)
                        .addValue("submittedAt", Timestamp.from(submittedAt)));
    }
}
