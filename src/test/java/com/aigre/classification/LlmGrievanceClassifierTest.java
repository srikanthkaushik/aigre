package com.aigre.classification;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Fast structural smoke test (a handful of unambiguous live cases), not the accuracy
 * measurement -- that's ComplaintEvalHarnessTest's job (all 91 labeled complaints). This just
 * proves the prompt/parsing wiring works before trusting the full harness run.
 */
@SpringBootTest
class LlmGrievanceClassifierTest {

    @Autowired
    private LlmGrievanceClassifier classifier;

    @Test
    void straightforwardPotholeComplaintClassifiesConfidently() {
        ClassificationResult result = classifier.classify(
                "There's a large pothole on Main Street that's been there for two weeks and is damaging cars.");

        assertThat(result.department()).isEqualTo("DOT");
        assertThat(result.actionable()).isTrue();
        assertThat(result.isConfident()).isTrue();
    }

    @Test
    void gasLeakComplaintIsClassifiedCritical() {
        ClassificationResult result = classifier.classify("I smell gas near the manhole cover on Elm Street, it's pretty strong.");

        assertThat(result.priority()).isEqualTo("CRITICAL");
        assertThat(result.actionable()).isTrue();
    }

    @Test
    void vagueComplaintIsNotConfidentlyClassified() {
        ClassificationResult result = classifier.classify("Things have been bad on my street lately.");

        assertThat(result.isConfident()).isFalse();
    }

    @Test
    void pureComplimentIsNotActionable() {
        ClassificationResult result =
                classifier.classify("Great job on the new bike lane downtown, it's been really nice to use this month.");

        assertThat(result.actionable()).isFalse();
    }
}
