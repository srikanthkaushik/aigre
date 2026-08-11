package com.aigre.chat;

import jakarta.validation.constraints.NotBlank;

public record ChatQuestion(@NotBlank String question) {
}
