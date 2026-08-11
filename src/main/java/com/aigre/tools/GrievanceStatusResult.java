package com.aigre.tools;

import java.time.Instant;

public record GrievanceStatusResult(
        String id,
        String status,
        String departmentPredicted,
        String departmentConfirmed,
        boolean departmentValid,
        String category,
        String priority,
        Double classificationConfidence,
        String sentimentLabel,
        Instant slaDueAt,
        Instant submittedAt,
        Instant resolvedAt,
        String resolutionNotes,
        boolean citizenContactAvailable,
        String duplicateOfId) {
}
