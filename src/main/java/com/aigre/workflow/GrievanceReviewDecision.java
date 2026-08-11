package com.aigre.workflow;

import jakarta.validation.constraints.NotBlank;

/**
 * A supervisor's decision on a paused (NEEDS_CLARIFICATION-pending-review) grievance.
 * department/category/priority are optional -- omit any the supervisor agrees with the LLM's
 * prediction on; only the fields supplied override the classify node's output.
 */
public record GrievanceReviewDecision(
        String department,
        String category,
        String priority,
        @NotBlank String note,
        @NotBlank String reviewedBy) {
}
