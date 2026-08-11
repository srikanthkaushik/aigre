package com.aigre.intake;

import com.aigre.classification.ClassificationResult;
import com.aigre.classification.LlmGrievanceClassifier;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Classifier mocked so this tests the duplicate-linking wiring deterministically, not live LLM
 * output (see GrievanceWorkflowPauseResumeTest's javadoc for why -- same documented sampling
 * variance applies to the real classifier).
 */
@SpringBootTest
class GrievanceIntakeDuplicateTest {

    @Autowired
    private GrievanceIntakeService service;

    @MockitoBean
    private LlmGrievanceClassifier classifier;

    @Test
    void secondConfidentSubmissionInSameDepartmentAndCategoryIsLinkedAsDuplicate() {
        // A synthetic, guaranteed-unique category -- a realistic one like "road-surface" collided
        // with real DOT/road-surface rows already present in the seeded demo data (seed.sql),
        // which falls within the default 7-day duplicate window relative to whenever tests run.
        String category = "test-cat-" + UUID.randomUUID();
        ClassificationResult confident =
                new ClassificationResult("DOT", category, "MEDIUM", 0.9, "NEGATIVE", -0.3, true, "clear pothole report");
        when(classifier.classify(anyString())).thenReturn(confident);

        GrievanceIntakeResponse first =
                service.submit(new GrievanceIntakeRequest("There's a pothole on Elm Street.", null, null, null));
        assertThat(first.status()).isEqualTo("TRIAGED");
        assertThat(first.duplicateOfId()).isNull();
        assertThat(first.slaDueAt()).isNotNull();

        GrievanceIntakeResponse second = service.submit(
                new GrievanceIntakeRequest("Another pothole on Elm Street, same spot.", null, null, null));

        assertThat(second.status()).isEqualTo("DUPLICATE");
        assertThat(second.duplicateOfId()).isEqualTo(first.id());
        assertThat(second.slaDueAt())
                .as("a duplicate doesn't open a second SLA clock")
                .isNull();
    }

    @Test
    void notActionableSubmissionsAreNeverCheckedForDuplicates() {
        ClassificationResult notActionable =
                new ClassificationResult(null, null, null, -1.0, "POSITIVE", 0.5, false, "pure compliment");
        when(classifier.classify(anyString())).thenReturn(notActionable);

        GrievanceIntakeResponse first =
                service.submit(new GrievanceIntakeRequest("Great job on the new park!", null, null, null));
        GrievanceIntakeResponse second =
                service.submit(new GrievanceIntakeRequest("Really love the new park, thanks!", null, null, null));

        assertThat(first.status()).isEqualTo("NOT_ACTIONABLE");
        assertThat(second.status()).isEqualTo("NOT_ACTIONABLE");
        assertThat(second.duplicateOfId()).isNull();
    }
}
