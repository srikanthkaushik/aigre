package com.aigre.tools;

import java.time.Instant;

public record SlaStatusResult(
        String grievanceId,
        String status,
        String priority,
        Instant slaDueAt,
        boolean breached,
        Long hoursUntilOrPastDue) {
}
