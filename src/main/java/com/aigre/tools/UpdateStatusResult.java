package com.aigre.tools;

public record UpdateStatusResult(String grievanceId, String previousStatus, String newStatus, boolean success, String message) {
}
