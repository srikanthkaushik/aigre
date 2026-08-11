package com.aigre.sla;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class SlaCalculatorTest {

    private final SlaCalculator calculator = new SlaCalculator();

    @Test
    void criticalResolveDueIsFourHoursOut() {
        Instant submittedAt = Instant.parse("2026-08-10T09:00:00Z");
        Instant dueAt = calculator.resolveDueAt(Priority.CRITICAL, submittedAt);
        assertThat(dueAt).isEqualTo(Instant.parse("2026-08-10T13:00:00Z"));
    }

    @Test
    void lowResolveDueIsFifteenDaysOut() {
        Instant submittedAt = Instant.parse("2026-08-10T09:00:00Z");
        Instant dueAt = calculator.resolveDueAt(Priority.LOW, submittedAt);
        assertThat(dueAt).isEqualTo(Instant.parse("2026-08-25T09:00:00Z"));
    }

    @Test
    void ackDueIsAlwaysBeforeResolveDue() {
        Instant submittedAt = Instant.now();
        for (Priority priority : Priority.values()) {
            assertThat(calculator.ackDueAt(priority, submittedAt))
                    .isBefore(calculator.resolveDueAt(priority, submittedAt));
        }
    }
}
