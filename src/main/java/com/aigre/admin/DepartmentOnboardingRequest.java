package com.aigre.admin;

import jakarta.validation.constraints.NotBlank;

public record DepartmentOnboardingRequest(
        @NotBlank String id,
        @NotBlank String name,
        @NotBlank String shortName,
        @NotBlank String jurisdictionNotes,
        @NotBlank String sourceUrl) {
}
