package com.aigre.chat;

import jakarta.validation.constraints.NotBlank;

/**
 * department is null for the main, unscoped floating widget; set for an embedded
 * department-scoped widget (com.aigre.embed.EmbedChatController) -- validated against
 * DepartmentDirectory.departmentIds() by ChatController before it's trusted.
 *
 * citizenToken is null for an anonymous citizen or a fresh browser; when present, it's replayed
 * automatically by the frontend (ApiService) from a value silently issued at submission time
 * (CitizenTokenService) -- never typed by a human. An invalid/expired/unparseable token is
 * treated identically to a missing one by ChatController, never as an error.
 */
public record ChatQuestion(@NotBlank String question, String department, String citizenToken) {
}
