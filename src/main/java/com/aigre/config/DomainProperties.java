package com.aigre.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * The agency-specific wording of the active domain profile (aigre.domain.* in application.yml,
 * overridden by application-dmv.yml). Routing units stay "departments" in code and the database;
 * unitLabel is only what the UI calls them. The classifier prompt is chosen by the same prefix but
 * read directly by LlmGrievanceClassifier, so it isn't part of this record.
 */
@ConfigurationProperties("aigre.domain")
public record DomainProperties(
        String agencyName,
        String tagline,
        String footer,
        String unitLabel,
        String unitLabelPlural,
        List<String> chatSuggestions,
        // Allow an embed with no department, framed by every registered origin (EmbedChatController).
        boolean agencyWideEmbed) {

    public DomainProperties {
        chatSuggestions = chatSuggestions == null ? List.of() : List.copyOf(chatSuggestions);
    }
}
