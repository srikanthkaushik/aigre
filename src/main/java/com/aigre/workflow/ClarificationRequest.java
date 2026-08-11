package com.aigre.workflow;

import jakarta.validation.constraints.NotBlank;

public record ClarificationRequest(@NotBlank String additionalText) {
}
