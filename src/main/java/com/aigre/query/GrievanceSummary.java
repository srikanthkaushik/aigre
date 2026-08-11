package com.aigre.query;

import java.time.Instant;

public record GrievanceSummary(
        String id,
        String status,
        String department,
        String category,
        String priority,
        Double classificationConfidence,
        Instant slaDueAt,
        Instant submittedAt,
        String resolutionNotes,
        boolean breached) {
}
