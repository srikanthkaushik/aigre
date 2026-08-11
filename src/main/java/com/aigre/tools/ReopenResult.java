package com.aigre.tools;

import java.time.Instant;

public record ReopenResult(
        String grievanceId,
        String previousStatus,
        String newStatus,
        String previousPriority,
        String newPriority,
        Instant newSlaDueAt,
        boolean success,
        String message) {
}
