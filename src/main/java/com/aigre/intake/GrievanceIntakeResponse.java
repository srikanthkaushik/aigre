package com.aigre.intake;

import java.time.Instant;
import java.util.UUID;

public record GrievanceIntakeResponse(
        UUID id,
        String status,
        String departmentPredicted,
        String category,
        double classificationConfidence,
        String priority,
        Instant slaDueAt,
        UUID duplicateOfId) {
}
