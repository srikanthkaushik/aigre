package com.aigre.workflow;

import java.time.Instant;
import java.util.List;

public record GrievanceWorkflowResponse(
        String grievanceId,
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
        String duplicateOfId,
        String citizenToken) {
}
