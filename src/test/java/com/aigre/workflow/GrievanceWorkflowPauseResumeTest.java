package com.aigre.workflow;

import com.aigre.classification.ClassificationResult;
import com.aigre.classification.LlmGrievanceClassifier;
import com.aigre.intake.GrievanceIntakeRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Tests the interrupt/resume graph mechanics deterministically -- mocks LlmGrievanceClassifier
 * instead of depending on live low-confidence output, since classification confidence has
 * documented LLM sampling variance (see GrievanceWorkflowServiceTest's javadoc).
 */
@SpringBootTest
class GrievanceWorkflowPauseResumeTest {

    @Autowired
    private GrievanceWorkflowService service;

    @MockitoBean
    private LlmGrievanceClassifier classifier;

    @Test
    void lowConfidenceClassificationPausesForReviewThenCommitsOnResume() {
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
                        "DPW", "general-complaint", "LOW", "reviewed: vague, default triage to DPW", "supervisor-1"));

        assertThat(resumed.pendingReview()).isFalse();
        assertThat(resumed.status()).isEqualTo("TRIAGED");
        assertThat(resumed.department()).isEqualTo("DPW");
        assertThat(resumed.category()).isEqualTo("general-complaint");
        assertThat(resumed.priority()).isEqualTo("LOW");
        assertThat(resumed.slaDueAt()).isNotNull();
    }

    @Test
    void clarifyReclassifiesAndAutoResumesWhenNowConfident() {
        ClassificationResult vague = new ClassificationResult(
                null, null, null, 0.3, "NEUTRAL", 0.0, true, "too vague to identify a specific issue or department");
        ClassificationResult confidentAfterDetail = new ClassificationResult(
                "DOT", "road-surface", "MEDIUM", 0.9, "NEGATIVE", -0.3, true, "clearly a pothole on a city road now");
        when(classifier.classify(anyString())).thenReturn(vague, confidentAfterDetail);

        GrievanceWorkflowResponse started = service.start(
                new GrievanceIntakeRequest("Things have been bad on my street lately.", null, null, null));
        assertThat(started.pendingReview()).isTrue();

        GrievanceWorkflowResponse clarified =
                service.clarify(started.grievanceId(), "It's specifically a pothole on Elm Street near my house.");

        assertThat(clarified.pendingReview()).isFalse();
        assertThat(clarified.status()).isEqualTo("TRIAGED");
        assertThat(clarified.department()).isEqualTo("DOT");
        assertThat(clarified.category()).isEqualTo("road-surface");
        assertThat(clarified.priority()).isEqualTo("MEDIUM");
        assertThat(clarified.confidence()).isEqualTo(0.9);
        assertThat(clarified.reasoning()).isEqualTo("clearly a pothole on a city road now");
        assertThat(clarified.rawText())
                .contains("Things have been bad on my street lately.")
                .contains("It's specifically a pothole on Elm Street near my house.");
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
        assertThat(clarified.rawText()).contains("It's just generally bad.");
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
