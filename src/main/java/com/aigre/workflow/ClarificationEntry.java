package com.aigre.workflow;

import java.time.Instant;

/**
 * One citizen-supplied follow-up detail (see GrievanceWorkflowService.clarify()), ordered by
 * submittedAt. Kept distinct from the original grievance.raw_text so the employee dashboard can
 * render "original complaint" vs. "follow-up" as separate, clearly-labeled entries.
 */
public record ClarificationEntry(String text, Instant submittedAt) {
}
