package com.aigre.query;

import java.time.Instant;

/**
 * A grievance whose duplicate chain (grievances.duplicate_of_id, resolved via a recursive
 * walk-to-root -- see GrievanceTrendsService) has accumulated 3+ total reports. This is the
 * system's own existing definition of "the same issue reported again," reused here rather than
 * inventing a second, weaker signal.
 */
public record RecurringIssue(
        String grievanceId,
        String department,
        String category,
        String rawTextSnippet,
        Instant firstReported,
        int repeatCount) {
}
