package com.aigre.query;

import jakarta.validation.constraints.NotBlank;

public record UpdateStatusRequest(
        @NotBlank String newStatus,
        @NotBlank String note,
        @NotBlank String changedBy) {
}
