package com.aigre.chat;

import jakarta.validation.constraints.NotBlank;

/**
 * department is null for the main, unscoped floating widget; set for an embedded
 * department-scoped widget (com.aigre.embed.EmbedChatController) -- validated against
 * DepartmentDirectory.departmentIds() by ChatController before it's trusted.
 */
public record ChatQuestion(@NotBlank String question, String department) {
}
