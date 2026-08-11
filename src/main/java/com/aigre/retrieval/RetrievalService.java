package com.aigre.retrieval;

import com.aigre.metrics.LlmCallTimer;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Retrieve wide (hybrid vector+FTS via PgVectorEmbeddingStore's SearchMode.HYBRID),
 * rerank narrow with an LLM scoring pass. Cosine similarity alone can't judge
 * relevance — see plan §"design rules that earned their place".
 *
 * A cross-encoder rerank (langchain4j-onnx-scoring, ms-marco-MiniLM-L-6-v2) was
 * implemented and is the theoretically better fix for the cross-reference-competition
 * finding (see PROJECT.md), but is blocked on this machine by a genuine environment
 * issue: the shared JDK's bundled msvcp140.dll (14.31.31103.0) is older than what
 * onnxruntime 1.22.0's Windows native build requires (>=14.40), and Windows' DLL
 * search order checks java.exe's own bin/ directory before System32, so the JVM loads
 * the stale bundled copy even though the correct one (14.44.35211.0) is installed
 * system-wide. Confirmed via direct System.load() testing outside Spring; pre-loading
 * the correct DLL first did not resolve it (other dependent DLLs are likely also
 * affected). Not fixed here because the real fix means either modifying the shared
 * JDK install (used by other projects on this machine) or switching JDK
 * distributions — an environment decision, not a code change. Reverted to the LLM
 * rerank (still carries the cross-reference-competition limitation) to keep the app
 * running. See PROJECT.md for the full diagnosis if revisiting this.
 */
@Service
public class RetrievalService {

    private static final Pattern SCORE_PATTERN = Pattern.compile("SCORE:\\s*(\\d+)");

    private final EmbeddingModel embeddingModel;
    private final EmbeddingStore<TextSegment> embeddingStore;
    private final ChatModel chatModel;
    private final LlmCallTimer llmCallTimer;
    private final int initialK;
    private final int rerankTo;

    public RetrievalService(
            EmbeddingModel embeddingModel,
            EmbeddingStore<TextSegment> embeddingStore,
            ChatModel chatModel,
            LlmCallTimer llmCallTimer,
            @Value("${rag.retrieval.initial-k:15}") int initialK,
            @Value("${rag.retrieval.rerank-to:5}") int rerankTo) {
        this.embeddingModel = embeddingModel;
        this.embeddingStore = embeddingStore;
        this.chatModel = chatModel;
        this.llmCallTimer = llmCallTimer;
        this.initialK = initialK;
        this.rerankTo = rerankTo;
    }

    public List<RetrievedSource> retrieve(String query) {
        EmbeddingSearchRequest request = EmbeddingSearchRequest.builder()
                .queryEmbedding(embeddingModel.embed(query).content())
                .query(query)
                .maxResults(initialK)
                .build();

        List<EmbeddingMatch<TextSegment>> matches = embeddingStore.search(request).matches();

        return matches.stream()
                .map(match -> new RetrievedSource(
                        match.embedded().text(),
                        match.embedded().metadata().toMap(),
                        match.score(),
                        rerankScore(query, match.embedded().text())))
                .sorted(Comparator.comparingDouble(RetrievedSource::rerankScore).reversed())
                .limit(rerankTo)
                .toList();
    }

    /** Reason before verdict: the model explains itself, then emits SCORE: <n> on the final line. -1 if unparseable, never 0. */
    private int rerankScore(String query, String candidateText) {
        String prompt =
                """
                Rate how relevant this passage is to the question, on a scale of 0-10.
                Reason briefly, then give the score on the final line as: SCORE: <number>

                Question: %s

                Passage: %s
                """
                        .formatted(query, candidateText);
        String response = llmCallTimer.time("rerank", () -> chatModel.chat(prompt));
        Matcher matcher = SCORE_PATTERN.matcher(response);
        if (matcher.find()) {
            return Integer.parseInt(matcher.group(1));
        }
        return -1;
    }
}
