package com.aigre.chat;

import com.aigre.retrieval.RetrievedSource;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Exact-match (not semantic) cache in front of RetrievalService + the chat LLM call for
 * /chat/stream -- trims/collapses-whitespace/lowercases the question before keying, so trivial
 * formatting differences still hit, but this never judges two *different* questions as "close
 * enough" the way a semantic/embedding cache would (see PROJECT.md's "cosine can't judge
 * relevance" lesson, which applies just as much to comparing questions as to comparing documents).
 *
 * ChatController owns all eligibility rules for when this is consulted or written to --
 * personalized (citizen-recognized) and tool-triggering answers never reach get()/put() at all.
 */
@Component
public class ChatResponseCache {

    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    private final Cache<ChatCacheKey, CachedChatAnswer> cache;
    private final boolean enabled;

    public ChatResponseCache(
            @Value("${chat.cache.enabled:true}") boolean enabled,
            @Value("${chat.cache.ttl:30m}") Duration ttl,
            @Value("${chat.cache.max-size:500}") long maxSize) {
        this.enabled = enabled;
        this.cache = Caffeine.newBuilder().expireAfterWrite(ttl).maximumSize(maxSize).build();
    }

    public Optional<CachedChatAnswer> get(String question, String department) {
        if (!enabled) {
            return Optional.empty();
        }
        return Optional.ofNullable(cache.getIfPresent(keyFor(question, department)));
    }

    public void put(String question, String department, CachedChatAnswer answer) {
        if (!enabled) {
            return;
        }
        cache.put(keyFor(question, department), answer);
    }

    /** Called by CorpusIngestionService.reset() -- a cached answer can cite a document the corpus no longer has. */
    public void invalidateAll() {
        cache.invalidateAll();
    }

    private ChatCacheKey keyFor(String question, String department) {
        return new ChatCacheKey(normalize(question), department);
    }

    private String normalize(String question) {
        return WHITESPACE.matcher(question.trim()).replaceAll(" ").toLowerCase();
    }

    private record ChatCacheKey(String normalizedQuestion, String department) {
    }

    public record CachedChatAnswer(String answerText, List<RetrievedSource> sources) {
    }
}
