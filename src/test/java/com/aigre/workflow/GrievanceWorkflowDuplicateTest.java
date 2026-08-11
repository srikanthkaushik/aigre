package com.aigre.workflow;

import com.aigre.classification.ClassificationResult;
import com.aigre.classification.LlmGrievanceClassifier;
import com.aigre.intake.GrievanceIntakeRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Same duplicate-linking behavior as GrievanceIntakeDuplicateTest, exercised through the
 * LangGraph4j workflow's commit() node instead of the plain intake path -- the two commit paths
 * don't share code (see PROJECT.md), so both need independent coverage.
 */
@SpringBootTest
class GrievanceWorkflowDuplicateTest {

    @Autowired
    private GrievanceWorkflowService service;

    @MockitoBean
    private LlmGrievanceClassifier classifier;

    @Test
    void secondConfidentWorkflowSubmissionInSameDepartmentAndCategoryIsLinkedAsDuplicate() {
        // A synthetic, guaranteed-unique category -- see GrievanceIntakeDuplicateTest for why a
        // realistic one collided with the seeded demo data.
        String category = "test-cat-" + UUID.randomUUID();
        ClassificationResult confident =
                new ClassificationResult("DEP", category, "MEDIUM", 0.9, "NEGATIVE", -0.3, true, "clear dumping report");
        when(classifier.classify(anyString())).thenReturn(confident);

        GrievanceWorkflowResponse first =
                service.start(new GrievanceIntakeRequest("Someone dumped trash behind my house.", null, null, null));
        assertThat(first.status()).isEqualTo("TRIAGED");
        assertThat(first.slaDueAt()).isNotNull();

        GrievanceWorkflowResponse second = service.start(
                new GrievanceIntakeRequest("More dumping happened behind my house again.", null, null, null));

        assertThat(second.status()).isEqualTo("DUPLICATE");
        assertThat(second.duplicateOfId()).isEqualTo(first.grievanceId());
        assertThat(second.slaDueAt())
                .as("a duplicate doesn't open a second SLA clock")
                .isNull();
    }
}
