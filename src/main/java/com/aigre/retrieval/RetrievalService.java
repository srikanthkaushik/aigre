package com.aigre.retrieval;

import com.aigre.metrics.LlmCallTimer;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.filter.Filter;
import dev.langchain4j.store.embedding.filter.MetadataFilterBuilder;
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
 * rerank to keep the app running. See PROJECT.md for the full diagnosis if revisiting
 * this.
 *
 * The cross-reference-competition finding itself now has a real fix: CorpusIngestionService
 * strips deliberately-marked disambiguation spans ([[XREF]]...[[/XREF]] in the source .txt) out
 * of both the embedded text AND a "rerank_text" metadata field, while keeping the full original
 * prose in what's actually returned/shown. rerankScore() below reads "rerank_text" rather than
 * the candidate's own returned text for exactly that reason — an earlier version of this fix only
 * cleaned the embedded text and left rerank scoring untouched, which turned out to still be
 * swayed by disambiguation content it could still see.
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
        return retrieve(query, null);
    }

    /** department, when non-null, restricts retrieval to that department's own corpus (see CorpusIngestionService). */
    public List<RetrievedSource> retrieve(String query, String department) {
        Filter filter = department != null
                ? MetadataFilterBuilder.metadataKey("department").isEqualTo(department)
                : null;
        EmbeddingSearchRequest request = EmbeddingSearchRequest.builder()
                .queryEmbedding(llmCallTimer.time("embed", () -> embeddingModel.embed(query)).content())
                .query(query)
                .maxResults(initialK)
                .filter(filter)
                .build();

        List<EmbeddingMatch<TextSegment>> matches =
                llmCallTimer.time("vector_search", () -> embeddingStore.search(request)).matches();

        return matches.stream()
                .map(match -> new RetrievedSource(
                        match.embedded().text(),
                        match.embedded().metadata().toMap(),
                        match.score(),
                        rerankScore(query, rerankCandidateText(match.embedded()))))
                // rerankTo is a MAX, not a fixed count -- without this filter, a query where fewer
                // than rerankTo candidates are genuinely relevant still padded the result out with
                // whatever scored lowest (confirmed live: a query with only 2 relevant chunks
                // returned 3 more scored 3, 0, and 0 out of 10, just to fill the quota). A rerank
                // score of 0 is the LLM's own "not relevant" signal on its 0-10 scale, per the
                // rerank prompt itself -- excluding it (and the -1 unparseable sentinel) removes
                // the clearest false citations. Not a perfect fix: a middling score can still
                // outrank a genuinely relevant low-scored one (observed: an unrelated chunk at 3
                // outscored a relevant one at 2) -- the same LLM-judgment noise this project's
                // rerank step already accepts as better than cosine alone, not eliminated by it.
                .filter(source -> source.rerankScore() > 0)
                .sorted(Comparator.comparingDouble(RetrievedSource::rerankScore).reversed())
                .limit(rerankTo)
                .toList();
    }

    /**
     * "rerank_text" is set on every stored segment at ingestion time (CorpusIngestionService) --
     * the same disambiguation-stripped text used for embedding, kept separately since the
     * returned/answer-context text deliberately keeps the full original prose. Falls back to the
     * segment's own text for anything ingested before that metadata field existed.
     */
    private static String rerankCandidateText(TextSegment segment) {
        String cleaned = segment.metadata().getString("rerank_text");
        return cleaned != null ? cleaned : segment.text();
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
