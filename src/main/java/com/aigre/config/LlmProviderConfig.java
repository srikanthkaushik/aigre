package com.aigre.config;

import dev.langchain4j.model.anthropic.AnthropicChatModel;
import dev.langchain4j.model.anthropic.AnthropicStreamingChatModel;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.ollama.OllamaChatModel;
import dev.langchain4j.model.ollama.OllamaStreamingChatModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * llm.provider=ollama|anthropic selects which pair of beans is registered.
 * Default is ollama (see application.yml) — fully offline, no API key required.
 */
@Configuration
public class LlmProviderConfig {

    @Bean
    @ConditionalOnProperty(name = "llm.provider", havingValue = "ollama", matchIfMissing = true)
    public ChatModel ollamaChatModel(
            @Value("${ollama.base-url}") String baseUrl,
            @Value("${ollama.chat-model}") String modelName,
            @Value("${ollama.timeout:120s}") Duration timeout) {
        return OllamaChatModel.builder()
                .baseUrl(baseUrl)
                .modelName(modelName)
                .timeout(timeout)
                .build();
    }

    @Bean
    @ConditionalOnProperty(name = "llm.provider", havingValue = "ollama", matchIfMissing = true)
    public StreamingChatModel ollamaStreamingChatModel(
            @Value("${ollama.base-url}") String baseUrl,
            @Value("${ollama.chat-model}") String modelName,
            @Value("${ollama.timeout:120s}") Duration timeout) {
        return OllamaStreamingChatModel.builder()
                .baseUrl(baseUrl)
                .modelName(modelName)
                .timeout(timeout)
                .build();
    }

    @Bean
    @ConditionalOnProperty(name = "llm.provider", havingValue = "anthropic")
    public ChatModel anthropicChatModel(
            @Value("${anthropic.api-key:}") String apiKey,
            @Value("${anthropic.chat-model}") String modelName,
            @Value("${anthropic.timeout:60s}") Duration timeout) {
        return AnthropicChatModel.builder()
                .apiKey(apiKey)
                .modelName(modelName)
                .timeout(timeout)
                .build();
    }

    @Bean
    @ConditionalOnProperty(name = "llm.provider", havingValue = "anthropic")
    public StreamingChatModel anthropicStreamingChatModel(
            @Value("${anthropic.api-key:}") String apiKey,
            @Value("${anthropic.chat-model}") String modelName,
            @Value("${anthropic.timeout:60s}") Duration timeout) {
        return AnthropicStreamingChatModel.builder()
                .apiKey(apiKey)
                .modelName(modelName)
                .timeout(timeout)
                .build();
    }
}
