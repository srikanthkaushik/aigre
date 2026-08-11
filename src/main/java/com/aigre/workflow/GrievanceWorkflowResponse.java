package com.aigre.workflow;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record GrievanceWorkflowResponse(
        UUID grievanceId,
        String status,
        boolean pendingReview,
        String department,
        String category,
        String priority,
        double confidence,
        Instant slaDueAt,
        String reasoning,
        String rawText,
        List<ClarificationEntry> clarifications,
        UUID duplicateOfId) {
}
