package com.aigre.guardrail;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Deterministic, ground-truth-backed: the 4 PII-laced fixtures below are the exact raw_text
 * values from test-data/grievances/eval-complaints.jsonl (GRV-074..077, expected_redaction: true)
 * -- kept literal here rather than loaded from the file so this test doesn't depend on the
 * WebFlux/HTTP layer PiiRedactionWebFilter runs in (see that class's own test for the end-to-end
 * check).
 */
class PiiRedactorTest {

    private final PiiRedactor redactor = new PiiRedactor();

    @Test
    void redactsSsn() {
        PiiRedactor.Result result = redactor.redact(
                "My SSN is 123-45-6789 and I want to know why my benefits case is taking so long.");

        assertThat(result.text()).doesNotContain("123-45-6789").contains("[REDACTED-SSN]");
        assertThat(result.redactedTypes()).containsExactly("SSN");
    }

    @Test
    void redactsCreditCard() {
        PiiRedactor.Result result = redactor.redact(
                "Please charge my card 4111 1111 1111 1111 if there's a fee for expediting my "
                        + "code enforcement complaint about my apartment.");

        assertThat(result.text()).doesNotContain("4111 1111 1111 1111").contains("[REDACTED-CARD]");
        assertThat(result.redactedTypes()).containsExactly("CREDIT_CARD");
    }

    @Test
    void redactsEmail() {
        PiiRedactor.Result result = redactor.redact(
                "You can reach me at not.my.portal.email@fakemail.example instead of what's on "
                        + "file, it's easier for me, regarding the pothole on 8th St.");

        assertThat(result.text())
                .doesNotContain("not.my.portal.email@fakemail.example")
                .contains("[REDACTED-EMAIL]");
        assertThat(result.redactedTypes()).containsExactly("EMAIL");
    }

    @Test
    void redactsPhone() {
        PiiRedactor.Result result = redactor.redact(
                "My phone number is 555-013-4488, please call me about the noise complaint I "
                        + "filed regarding my neighbor's construction crew.");

        assertThat(result.text()).doesNotContain("555-013-4488").contains("[REDACTED-PHONE]");
        assertThat(result.redactedTypes()).containsExactly("PHONE");
    }

    @Test
    void leavesOrdinaryComplaintTextUnchanged() {
        String text = "There's a large pothole on Maple Street in front of 1234 Main St that's "
                + "been there for two weeks.";

        PiiRedactor.Result result = redactor.redact(text);

        assertThat(result.text()).isEqualTo(text);
        assertThat(result.redactedTypes()).isEmpty();
    }

    @Test
    void redactsMultipleTypesInOneText() {
        PiiRedactor.Result result = redactor.redact(
                "SSN 123-45-6789, call me at 555-013-4488 or email me@example.com.");

        assertThat(result.redactedTypes()).containsExactlyInAnyOrder("SSN", "PHONE", "EMAIL");
        assertThat(result.text())
                .contains("[REDACTED-SSN]")
                .contains("[REDACTED-PHONE]")
                .contains("[REDACTED-EMAIL]");
    }
}
