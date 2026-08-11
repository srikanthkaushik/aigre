package com.aigre.intake;

import jakarta.validation.constraints.NotBlank;

public record GrievanceIntakeRequest(
        @NotBlank String rawText,
        String citizenName,
        String citizenEmail,
        String citizenPhone) {
}
