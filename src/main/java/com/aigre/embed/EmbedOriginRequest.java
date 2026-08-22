package com.aigre.embed;

import jakarta.validation.constraints.NotBlank;

public record EmbedOriginRequest(@NotBlank String origin) {
}
