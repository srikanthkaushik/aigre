package com.aigre.sla;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PriorityTest {

    @Test
    void oneTierUpMovesTowardMoreSevere() {
        assertThat(Priority.LOW.oneTierUp()).isEqualTo(Priority.MEDIUM);
        assertThat(Priority.MEDIUM.oneTierUp()).isEqualTo(Priority.HIGH);
        assertThat(Priority.HIGH.oneTierUp()).isEqualTo(Priority.CRITICAL);
    }

    @Test
    void criticalHasNoTierAboveItAndStaysCritical() {
        assertThat(Priority.CRITICAL.oneTierUp()).isEqualTo(Priority.CRITICAL);
    }
}
