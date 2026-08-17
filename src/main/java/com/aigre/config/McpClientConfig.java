package com.aigre.config;

import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.tool.ToolProvider;
import com.aigre.chat.CitizenChatAssistant;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * MCP client side of AIGRE's own MCP server (milestone 3's {@code GrievanceMcpTools}) --
 * consumed by the citizen chat so it can look up live grievance data instead of only ever
 * answering from the static ingested policy corpus. Self-referential: same JVM/port as the
 * server it connects to (see LazyGrievanceToolProvider for how the port is resolved).
 */
@Configuration
public class McpClientConfig {

    /**
     * See LazyGrievanceToolProvider's javadoc for why this connects lazily, and resolves its own
     * URL lazily, on first use rather than eagerly at bean-construction time.
     */
    @Bean
    public ToolProvider grievanceReadOnlyToolProvider(Environment environment) {
        return new LazyGrievanceToolProvider(environment);
    }

    @Bean
    public CitizenChatAssistant citizenChatAssistant(
            StreamingChatModel streamingChatModel, ToolProvider grievanceReadOnlyToolProvider) {
        return AiServices.builder(CitizenChatAssistant.class)
                .streamingChatModel(streamingChatModel)
                .toolProvider(grievanceReadOnlyToolProvider)
                .maxToolCallingRoundTrips(3)
                .build();
    }
}
