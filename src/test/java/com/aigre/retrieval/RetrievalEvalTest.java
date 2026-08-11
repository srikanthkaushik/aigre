package com.aigre.retrieval;

import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Eval question #1 from plan §3.2: "How long does DOT have to repair a
 * reported pothole once it's submitted?" must retrieve the DOT pothole SOP,
 * not the DPW sidewalk distractor. Full corpus generation is a later pass;
 * this seeds the two documents directly so the retrieval+rerank pipeline
 * itself is proven on day one.
 *
 * Requires the live aigre-pg container and a running Ollama with
 * nomic-embed-text and the configured chat model pulled.
 *
 * <p><b>WARNING — destructive against the shared dev database.</b>
 * {@link #seedFixture()} calls {@code embeddingStore.removeAll()} and
 * replaces the entire {@code rag_documents} table with these 2 fixture
 * rows, wiping whatever real corpus was ingested via
 * {@code POST /ingest/reset}. Confirmed the hard way: running this test
 * silently reduced a live 108-doc/543-chunk corpus down to 2 rows and broke
 * chat citations elsewhere in the running app. Re-run
 * {@code POST /ingest/reset?confirm=true} afterward if you need the real
 * corpus back.
 */
@SpringBootTest
class RetrievalEvalTest {

    @Autowired
    private EmbeddingModel embeddingModel;

    @Autowired
    private EmbeddingStore<TextSegment> embeddingStore;

    @Autowired
    private RetrievalService retrievalService;

    @BeforeEach
    void seedFixture() {
        embeddingStore.removeAll();

        TextSegment dotPotholeSop = TextSegment.from(
                "DOT Road Maintenance SOP v2 (current). Potholes reported through the citizen portal "
                        + "must be repaired within 5 business days of the initial report, per city code 14-2.",
                Metadata.from("department", "DOT"));
        TextSegment dpwSidewalkDistractor = TextSegment.from(
                "DPW Sidewalk Maintenance Policy. Cracked or uneven sidewalk pavement reported by a "
                        + "citizen must be inspected within 10 business days and scheduled for repair.",
                Metadata.from("department", "DPW"));

        embeddingStore.add(embeddingModel.embed(dotPotholeSop).content(), dotPotholeSop);
        embeddingStore.add(embeddingModel.embed(dpwSidewalkDistractor).content(), dpwSidewalkDistractor);
    }

    @Test
    void potholeSlaQuestionRetrievesDotSopNotDpwDistractor() {
        List<RetrievedSource> results =
                retrievalService.retrieve("How long does DOT have to repair a reported pothole once it's submitted?");

        assertThat(results).isNotEmpty();
        assertThat(results.get(0).text()).contains("DOT Road Maintenance SOP");
        assertThat(results.get(0).metadata()).containsEntry("department", "DOT");
    }
}
