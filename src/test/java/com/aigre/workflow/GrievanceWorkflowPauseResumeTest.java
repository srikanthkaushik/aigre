package com.aigre.workflow;

import com.aigre.classification.ClassificationResult;
import com.aigre.classification.LlmGrievanceClassifier;
import com.aigre.duplicate.DuplicateDetectionService;
import com.aigre.intake.GrievanceIntakeRequest;
import com.aigre.query.GrievanceQueryService;
import com.aigre.query.GrievanceSummary;
import com.aigre.sla.SlaCalculator;
import com.aigre.tools.GrievanceMcpTools;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.GraphInput;
import org.bsc.langgraph4j.RunnableConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import javax.sql.DataSource;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Tests the interrupt/resume graph mechanics deterministically -- mocks LlmGrievanceClassifier
 * instead of depending on live low-confidence output, since classification confidence has
 * documented LLM sampling variance (see GrievanceWorkflowServiceTest's javadoc).
 *
 * Tests that resolve to a real department+category use a fresh random category per run (not a
 * fixed string like "general-complaint") -- duplicate detection (DuplicateDetectionService) is
 * department+category-based, so a repeated `mvn test` run would otherwise match the previous
 * run's still-open leftover row (these tests don't clean up after themselves) and land on
 * DUPLICATE instead of the TRIAGED this class is actually testing for.
 */
@SpringBootTest
class GrievanceWorkflowPauseResumeTest {

    @Autowired
    private GrievanceWorkflowService service;

    @Autowired
    private GrievanceQueryService queryService;

    @Autowired
    private SlaCalculator slaCalculator;

    @Autowired
    private NamedParameterJdbcTemplate jdbc;

    @Autowired
    private DuplicateDetectionService duplicateDetectionService;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private GrievanceMcpTools grievanceMcpTools;

    @MockitoBean
    private LlmGrievanceClassifier classifier;

    @Test
    void lowConfidenceClassificationPausesForReviewThenCommitsOnResume() {
        String category = "test-cat-" + UUID.randomUUID();
        when(classifier.classify(anyString())).thenReturn(new ClassificationResult(
                null, null, null, 0.3, "NEUTRAL", 0.0, true,
                "too vague to identify a specific issue or department"));

        GrievanceWorkflowResponse started = service.start(
                new GrievanceIntakeRequest("Things have been bad on my street lately.", null, null, null));

        assertThat(started.pendingReview()).isTrue();
        assertThat(started.status()).isEqualTo("NEW");
        assertThat(started.reasoning()).isEqualTo("too vague to identify a specific issue or department");

        GrievanceWorkflowResponse resumed = service.resume(
                started.grievanceId(),
                new GrievanceReviewDecision(
                        "DPW", category, "LOW", "reviewed: vague, default triage to DPW", "supervisor-1"));

        assertThat(resumed.pendingReview()).isFalse();
        assertThat(resumed.status()).isEqualTo("TRIAGED");
        assertThat(resumed.department()).isEqualTo("DPW");
        assertThat(resumed.category()).isEqualTo(category);
        assertThat(resumed.priority()).isEqualTo("LOW");
        assertThat(resumed.slaDueAt()).isNotNull();
    }

    /**
     * Regression test for a real bug: department_predicted used to stay NULL for the entire time
     * a low-confidence case sat paused (only commit() wrote it, and commit() doesn't run until
     * after a human resumes) -- so GrievanceQueryService.list()'s WHERE department = :department
     * filter never matched, making a paused case invisible in every department-scoped employee's
     * Pending Review queue, even when the LLM's own guess (just not confident enough to
     * auto-route) already pointed at a real department. Fixed by GrievanceWorkflowGraphConfig
     * .persistPredictedClassification(), called from classify() before the graph ever reaches the
     * interrupt point.
     */
    @Test
    void pausedGrievanceWithADepartmentGuessIsVisibleInThatDepartmentsQueueBeforeAnyResume() {
        String category = "test-cat-" + UUID.randomUUID();
        when(classifier.classify(anyString())).thenReturn(new ClassificationResult(
                "DOT", category, "MEDIUM", 0.3, "NEUTRAL", 0.0, true,
                "leans DOT but not confident enough to auto-route"));

        GrievanceWorkflowResponse started = service.start(
                new GrievanceIntakeRequest("Something about a road maybe, not entirely sure.", null, null, null));

        assertThat(started.pendingReview()).isTrue();

        List<GrievanceSummary> dotQueue = queryService.list("DOT", null);
        assertThat(dotQueue).extracting(GrievanceSummary::id).contains(started.grievanceId().toString());
    }

    /**
     * Regression test for the other half of "paused reviews survive a restart": proves the
     * checkpoint really lives in Postgres, not just in the JVM heap of the CompiledGraph bean
     * `service` happens to hold. Simulates an app restart by hand-constructing a second, fully
     * independent GrievanceWorkflowGraphConfig (bypassing Spring's singleton bean cache) and
     * calling grievanceWorkflowGraph() on it directly -- this produces a brand-new
     * PostgresSaver/CompiledGraph object with zero shared in-memory state with the one `service`
     * uses. Resuming against that second object only works if the checkpoint was actually read
     * back from lg4jthread/lg4jcheckpoint. Under the old in-memory MemorySaver this exact test
     * would fail: a fresh `new MemorySaver()` starts with an empty map and has no knowledge of
     * checkpoints written by a different MemorySaver instance.
     */
    @Test
    void pausedWorkflowResumesAgainstAnIndependentlyConstructedGraph_provingItSurvivesARestart() throws Exception {
        when(classifier.classify(anyString())).thenReturn(new ClassificationResult(
                null, null, null, 0.3, "NEUTRAL", 0.0, true,
                "too vague to identify a specific issue or department"));

        GrievanceWorkflowResponse started = service.start(
                new GrievanceIntakeRequest("Things have been bad on my street lately.", null, null, null));
        assertThat(started.pendingReview()).isTrue();

        CompiledGraph<GrievanceWorkflowState> freshGraph = new GrievanceWorkflowGraphConfig(
                classifier, slaCalculator, jdbc, duplicateDetectionService, dataSource, grievanceMcpTools)
                .grievanceWorkflowGraph();

        RunnableConfig config = RunnableConfig.builder().threadId(started.grievanceId().toString()).build();

        // Assert the second, independent graph object can already see the paused checkpoint
        // *before* resuming -- proves the second instance genuinely read it back from Postgres,
        // not just that resume happened to work for some unrelated reason.
        assertThat(freshGraph.getState(config).next()).isEqualTo(GrievanceWorkflowGraphConfig.HUMAN_REVIEW_NODE);

        String category = "test-cat-" + UUID.randomUUID();
        freshGraph.invoke(GraphInput.resume(Map.of(
                "reviewedDepartment", "DPW",
                "reviewedCategory", category,
                "reviewedPriority", "LOW",
                "reviewNote", "reviewed via an independently-constructed graph",
                "reviewedBy", "supervisor-1")), config);

        // Assert straight off the database too, not only through GrievanceWorkflowResponse/
        // buildResponse() -- so this test isn't solely trusting the same response-building code
        // path every other test in this file already exercises.
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT status, department_confirmed, category, priority FROM grievances WHERE id = :id",
                Map.of("id", started.grievanceId()));
        assertThat(row.get("status")).isEqualTo("TRIAGED");
        assertThat(row.get("department_confirmed")).isEqualTo("DPW");
        assertThat(row.get("category")).isEqualTo(category);
        assertThat(row.get("priority")).isEqualTo("LOW");

        GrievanceWorkflowResponse afterRestart = service.status(started.grievanceId());
        assertThat(afterRestart.pendingReview()).isFalse();
        assertThat(afterRestart.status()).isEqualTo("TRIAGED");
        assertThat(afterRestart.department()).isEqualTo("DPW");
        assertThat(afterRestart.category()).isEqualTo(category);
        assertThat(afterRestart.priority()).isEqualTo("LOW");
    }

    @Test
    void clarifyReclassifiesAndAutoResumesWhenNowConfident() {
        String category = "test-cat-" + UUID.randomUUID();
        ClassificationResult vague = new ClassificationResult(
                null, null, null, 0.3, "NEUTRAL", 0.0, true, "too vague to identify a specific issue or department");
        ClassificationResult confidentAfterDetail = new ClassificationResult(
                "DOT", category, "MEDIUM", 0.9, "NEGATIVE", -0.3, true, "clearly a pothole on a city road now");
        when(classifier.classify(anyString())).thenReturn(vague, confidentAfterDetail);

        GrievanceWorkflowResponse started = service.start(
                new GrievanceIntakeRequest("Things have been bad on my street lately.", null, null, null));
        assertThat(started.pendingReview()).isTrue();

        GrievanceWorkflowResponse clarified =
                service.clarify(started.grievanceId(), "It's specifically a pothole on Elm Street near my house.");

        assertThat(clarified.pendingReview()).isFalse();
        assertThat(clarified.status()).isEqualTo("TRIAGED");
        assertThat(clarified.department()).isEqualTo("DOT");
        assertThat(clarified.category()).isEqualTo(category);
        assertThat(clarified.priority()).isEqualTo("MEDIUM");
        assertThat(clarified.confidence()).isEqualTo(0.9);
        assertThat(clarified.reasoning()).isEqualTo("clearly a pothole on a city road now");
        assertThat(clarified.rawText()).isEqualTo("Things have been bad on my street lately.");
        assertThat(clarified.clarifications()).hasSize(1);
        assertThat(clarified.clarifications().get(0).text())
                .isEqualTo("It's specifically a pothole on Elm Street near my house.");
    }

    @Test
    void clarifyStaysPendingWhenStillNotConfident() {
        ClassificationResult vague = new ClassificationResult(
                null, null, null, 0.3, "NEUTRAL", 0.0, true, "too vague to identify a specific issue or department");
        when(classifier.classify(anyString())).thenReturn(vague, vague);

        GrievanceWorkflowResponse started = service.start(
                new GrievanceIntakeRequest("Things have been bad on my street lately.", null, null, null));

        GrievanceWorkflowResponse clarified = service.clarify(started.grievanceId(), "It's just generally bad.");

        assertThat(clarified.pendingReview()).isTrue();
        assertThat(clarified.status()).isEqualTo("NEW");
        assertThat(clarified.rawText()).isEqualTo("Things have been bad on my street lately.");
        assertThat(clarified.clarifications()).hasSize(1);
        assertThat(clarified.clarifications().get(0).text()).isEqualTo("It's just generally bad.");
    }

    @Test
    void notActionableClassificationSkipsReviewAndCommitsAsNotActionable() {
        when(classifier.classify(anyString())).thenReturn(new ClassificationResult(
                null, null, null, -1.0, "POSITIVE", 0.5, false, "pure compliment, no actionable issue"));

        GrievanceWorkflowResponse response = service.start(
                new GrievanceIntakeRequest("Just wanted to say the new park looks great!", null, null, null));

        assertThat(response.pendingReview()).isFalse();
        assertThat(response.status()).isEqualTo("NOT_ACTIONABLE");
        assertThat(response.department()).isNull();
        assertThat(response.slaDueAt()).isNull();
    }
}
