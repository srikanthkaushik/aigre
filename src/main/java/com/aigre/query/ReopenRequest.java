package com.aigre.query;

import jakarta.validation.constraints.NotBlank;

public record ReopenRequest(
        @NotBlank String reason,
        @NotBlank String reopenedBy) {
}
