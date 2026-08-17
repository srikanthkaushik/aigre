package com.aigre.config;

import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.tool.ToolProvider;
import com.aigre.chat.CitizenChatAssistant;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MCP client side of AIGRE's own MCP server (milestone 3's {@code GrievanceMcpTools}) --
 * consumed by the citizen chat so it can look up live grievance data instead of only ever
 * answering from the static ingested policy corpus. Self-referential: same JVM/port as the
 * server it connects to (mcp.client.grievance-url defaults to http://localhost:8085/mcp).
 */
@Configuration
public class McpClientConfig {

    /**
     * See LazyGrievanceToolProvider's javadoc for why this connects lazily on first use rather
     * than eagerly at bean-construction time.
     */
    @Bean
    public ToolProvider grievanceReadOnlyToolProvider(@Value("${mcp.client.grievance-url}") String mcpUrl) {
        return new LazyGrievanceToolProvider(mcpUrl);
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
