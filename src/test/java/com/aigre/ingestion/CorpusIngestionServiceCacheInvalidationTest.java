package com.aigre.ingestion;

import com.aigre.chat.ChatResponseCache;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Confirms CorpusIngestionService.reset() actually calls through to
 * ChatResponseCache.invalidateAll() -- a cached chat answer can cite a document the corpus no
 * longer has once reset() wipes and reseeds the vector store.
 *
 * Not a fast test: reset() wipes and fully reingests the real declared corpus (108 documents at
 * the time this was written), same cost as the manual `POST /ingest/reset?confirm=true` call
 * documented in RUNNING.md/PROJECT.md. Deliberately kept out of ChatControllerTest (which stays
 * cheap) rather than silently making every routine `mvn test` run pay this cost -- same reasoning
 * RagEvalSuiteTest's own javadoc already documents for a different expensive suite. Run
 * deliberately: mvn test -Dtest=CorpusIngestionServiceCacheInvalidationTest
 */
@SpringBootTest
class CorpusIngestionServiceCacheInvalidationTest {

    @Autowired
    private CorpusIngestionService corpusIngestionService;

    @Autowired
    private ChatResponseCache chatCache;

    @Test
    void resetInvalidatesEveryCachedChatAnswer() {
        chatCache.put(
                "primed question for reset test", "DOT",
                new ChatResponseCache.CachedChatAnswer("primed answer", List.of()));
        assertThat(chatCache.get("primed question for reset test", "DOT")).isPresent();

        corpusIngestionService.reset();

        assertThat(chatCache.get("primed question for reset test", "DOT")).isEmpty();
    }
}
