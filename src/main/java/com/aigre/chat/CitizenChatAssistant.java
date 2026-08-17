package com.aigre.chat;

import dev.langchain4j.service.TokenStream;
import dev.langchain4j.service.UserMessage;

/**
 * AiServices-backed citizen chat, with read-only MCP tool access to AIGRE's own live grievance
 * data (see McpClientConfig) -- for questions RAG's static corpus can't answer, e.g. "what's the
 * status of grievance &lt;id&gt;". TokenStream both streams the answer prose token-by-token AND
 * supports tool-calling: onToolExecuted fires as an aside when a tool actually runs, so no
 * separate non-streamed code path is needed for the RAG-only case.
 */
public interface CitizenChatAssistant {
    TokenStream chat(@UserMessage String userMessage);
}
