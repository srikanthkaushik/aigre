package com.aigre.workflow;

import com.aigre.intake.GrievanceIntakeRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Live end-to-end test against Ollama (llm.provider default) and aigre-pg for the auto-commit
 * (high-confidence) path. The pause/resume path is tested separately in
 * GrievanceWorkflowPauseResumeTest against a mocked classifier -- routing into human_review
 * depends on classification confidence, which has documented LLM sampling variance
 * (ComplaintEvalHarnessTest); coupling the interrupt/resume *mechanics* test to live model output
 * would make it flaky for reasons unrelated to what it's actually testing.
 */
@SpringBootTest
class GrievanceWorkflowServiceTest {

    @Autowired
    private GrievanceWorkflowService service;

    @Test
    void confidentComplaintAutoCommitsWithoutReview() {
        GrievanceWorkflowResponse response = service.start(new GrievanceIntakeRequest(
                "There's a large pothole on Maple Street in front of 214 that's been there for "
                        + "two weeks and is damaging car tires.",
                null, null, null));

        assertThat(response.pendingReview()).isFalse();
        // TRIAGED on a fresh submission; DUPLICATE if an earlier run of this same test already
        // reported the same DOT/road-surface issue within the duplicate-detection window (real
        // LLM output here, not mocked, so the category can't be made artificially unique per run
        // the way the mocked-classifier tests are) -- both are valid "committed without pausing
        // for review" outcomes for what this test actually checks.
        assertThat(response.status()).isIn("TRIAGED", "DUPLICATE");
        assertThat(response.department()).isEqualTo("DOT");
        assertThat(response.priority()).isNotNull();
        if ("TRIAGED".equals(response.status())) {
            assertThat(response.slaDueAt()).isNotNull();
        } else {
            assertThat(response.duplicateOfId()).isNotNull();
        }
    }

}
