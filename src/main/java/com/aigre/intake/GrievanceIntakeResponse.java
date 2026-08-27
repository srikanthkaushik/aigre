package com.aigre.intake;

import java.time.Instant;

public record GrievanceIntakeResponse(
        String id,
        String status,
        String departmentPredicted,
        String category,
        double classificationConfidence,
        String priority,
        Instant slaDueAt,
        String duplicateOfId,
        String citizenToken) {
}
