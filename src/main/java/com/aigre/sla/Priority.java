package com.aigre.sla;

/**
 * Priority rubric and SLA table — plan.md §1.5. Deterministic, no LLM involved.
 */
public enum Priority {
    CRITICAL(1, 4),
    HIGH(4, 24),
    MEDIUM(24, 120),
    LOW(48, 360);

    public final int ackHours;
    public final int resolveHours;

    Priority(int ackHours, int resolveHours) {
        this.ackHours = ackHours;
        this.resolveHours = resolveHours;
    }
}
