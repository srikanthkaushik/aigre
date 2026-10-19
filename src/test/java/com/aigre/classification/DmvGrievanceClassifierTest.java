package com.aigre.classification;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The dmv profile's counterpart to LlmGrievanceClassifierTest: a handful of unambiguous live cases
 * through prompts/dmv/classifier.txt and the five bureau rows, proving the profile's prompt and
 * DEPARTMENTS wiring before trusting a full ComplaintEvalHarnessTest run on the 42 DMV complaints.
 * Each case sits on one of the bureau boundaries the prompt spells out, so a prompt or seed that
 * fell back to the city's would fail here rather than pass by accident.
 *
 * Only runs under the dmv profile, against the DMV database:
 *   mvn test -Dtest=DmvGrievanceClassifierTest -Dspring.profiles.active=dmv
 *     -Dspring.datasource.url=jdbc:postgresql://localhost:5434/aigre_dmv
 */
@SpringBootTest
@EnabledIfSystemProperty(named = "spring.profiles.active", matches = "dmv")
class DmvGrievanceClassifierTest {

    @Autowired
    private LlmGrievanceClassifier classifier;

    @Test
    void lostTitleRoutesConfidentlyToTitle() {
        ClassificationResult result = classifier.classify(
                "I lost my car title when I moved last year and now I need it to sell the car. How do I get a replacement?");

        assertThat(result.department()).isEqualTo("TITLE");
        assertThat(result.actionable()).isTrue();
        assertThat(result.isConfident()).isTrue();
    }

    @Test
    void paidRestorationStillSuspendedIsFinancialResponsibilityNotLicensing() {
        // The DL/FR boundary: the citizen calls it a license problem, but suspension and
        // restoration are FR's. Can't legally drive because a paid restoration wasn't processed = HIGH.
        ClassificationResult result = classifier.classify(
                "I paid my restoration fee last week and my license still shows suspended, so I can't drive to work.");

        assertThat(result.department()).isEqualTo("FR");
        assertThat(result.priority()).isEqualTo("HIGH");
    }

    @Test
    void vehicleRegisteredInSomeoneElsesNameIsCritical() {
        ClassificationResult result = classifier.classify(
                "Someone registered a car in my name at an address I have never lived at, and I just got a parking notice for it.");

        assertThat(result.priority()).isEqualTo("CRITICAL");
        assertThat(result.actionable()).isTrue();
    }

    @Test
    void vagueDmvComplaintIsNotConfidentlyClassified() {
        ClassificationResult result = classifier.classify("The DMV messed up my paperwork again.");

        assertThat(result.isConfident()).isFalse();
    }

    @Test
    void potholeIsNotTheDmvsToHandle() {
        // A city service: the city profile would route this to DOT; the DMV prompt must reject it.
        ClassificationResult result = classifier.classify("There's a huge pothole on Route 3 that blew out my tire.");

        assertThat(result.actionable()).isFalse();
    }
}
