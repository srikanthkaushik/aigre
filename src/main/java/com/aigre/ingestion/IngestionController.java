package com.aigre.ingestion;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Full wipe-and-rebuild of the RAG corpus from rag.ingest.corpus-path. ADMIN-only (SecurityConfig)
 * and refuses to run without ?confirm=true, because it empties rag_documents first and chat has
 * nothing to retrieve until the re-embed finishes.
 */
@RestController
@RequestMapping("/ingest")
public class IngestionController {

    private final CorpusIngestionService ingestionService;

    public IngestionController(CorpusIngestionService ingestionService) {
        this.ingestionService = ingestionService;
    }

    @PostMapping("/reset")
    public Mono<ResponseEntity<?>> reset(@RequestParam(defaultValue = "false") boolean confirm) {
        if (!confirm) {
            return Mono.just(ResponseEntity.badRequest()
                    .body("This wipes and reseeds the RAG corpus. Call with ?confirm=true to proceed."));
        }
        return Mono.fromCallable(ingestionService::reset)
                .subscribeOn(Schedulers.boundedElastic())
                .map(ResponseEntity::ok);
    }
}
