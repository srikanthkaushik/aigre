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
import org.springframework.context.annotation.Primary;

import java.time.Duration;

/**
 * llm.provider=ollama|anthropic selects which pair of beans is registered.
 * Default is ollama (see application.yml) — fully offline, no API key required.
 *
 * <p>Two {@link ChatModel} beans exist per provider, not one: the {@code @Primary}
 * "general" bean (RAG rerank in RetrievalService, and every unqualified {@code ChatModel}
 * injection point) and a "classification" bean, qualified by name, injected only into
 * LlmGrievanceClassifier. They're split because a single shared model was tried and
 * reverted — see PROJECT.md's "DMV title-correction misclassification" writeup: rerank
 * calls the model once per retrieved candidate per chat question, far more often than
 * classification's once per grievance, so a slower/more-accurate model that's a clear win
 * for classification (qwen3:8b vs. qwen2.5:7b, ~91.9% vs. ~65.9–86.8% measured accuracy)
 * regressed RAG badly when it was also the rerank model. The Anthropic classification bean
 * reuses the same model as the general one — claude-sonnet-5 is already the more-accurate,
 * lower-latency choice for both, so there's no equivalent split to make there.
 */
@Configuration
public class LlmProviderConfig {

    @Bean
    @Primary
    @ConditionalOnProperty(name = "llm.provider", havingValue = "ollama", matchIfMissing = true)
    public ChatModel ollamaChatModel(
            @Value("${ollama.base-url}") String baseUrl,
            @Value("${ollama.chat-model}") String modelName,
            @Value("${ollama.timeout:120s}") Duration timeout,
            @Value("${ollama.num-ctx:4096}") Integer numCtx) {
        return OllamaChatModel.builder()
                .baseUrl(baseUrl)
                .modelName(modelName)
                .timeout(timeout)
                .numCtx(numCtx)
                .build();
    }

    @Bean("classificationChatModel")
    @ConditionalOnProperty(name = "llm.provider", havingValue = "ollama", matchIfMissing = true)
    public ChatModel ollamaClassificationChatModel(
            @Value("${ollama.base-url}") String baseUrl,
            @Value("${ollama.classification-chat-model}") String modelName,
            @Value("${ollama.timeout:120s}") Duration timeout,
            @Value("${ollama.num-ctx:4096}") Integer numCtx) {
        return OllamaChatModel.builder()
                .baseUrl(baseUrl)
                .modelName(modelName)
                .timeout(timeout)
                .numCtx(numCtx)
                .build();
    }

    @Bean
    @ConditionalOnProperty(name = "llm.provider", havingValue = "ollama", matchIfMissing = true)
    public StreamingChatModel ollamaStreamingChatModel(
            @Value("${ollama.base-url}") String baseUrl,
            @Value("${ollama.chat-model}") String modelName,
            @Value("${ollama.timeout:120s}") Duration timeout,
            @Value("${ollama.num-ctx:4096}") Integer numCtx) {
        return OllamaStreamingChatModel.builder()
                .baseUrl(baseUrl)
                .modelName(modelName)
                .timeout(timeout)
                .numCtx(numCtx)
                .build();
    }

    @Bean
    @Primary
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

    @Bean("classificationChatModel")
    @ConditionalOnProperty(name = "llm.provider", havingValue = "anthropic")
    public ChatModel anthropicClassificationChatModel(
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
