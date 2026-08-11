package com.aigre.sla;

import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * Pure function: (priority, submitted-at) -> due-at. No LLM, no I/O.
 *
 * Calendar-hour based for day one. Business-hours-aware SLA calendar is an
 * explicit open item flagged in PROJECT.md — revisit before this becomes the
 * source of truth for real SLA breach reporting.
 */
@Component
public class SlaCalculator {

    public Instant ackDueAt(Priority priority, Instant submittedAt) {
        return submittedAt.plusSeconds(priority.ackHours * 3600L);
    }

    public Instant resolveDueAt(Priority priority, Instant submittedAt) {
        return submittedAt.plusSeconds(priority.resolveHours * 3600L);
    }
}
