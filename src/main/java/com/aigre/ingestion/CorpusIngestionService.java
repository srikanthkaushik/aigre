package com.aigre.ingestion;

import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.DocumentSplitter;
import dev.langchain4j.data.document.loader.FileSystemDocumentLoader;
import dev.langchain4j.data.document.parser.apache.tika.ApacheTikaDocumentParser;
import dev.langchain4j.data.document.splitter.DocumentSplitters;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.EmbeddingStoreIngestor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * RAG knowledge-corpus ingestion (policy/SOP/FAQ documents -> pgvector).
 * Deliberately separate from com.aigre.intake, which writes citizen
 * complaints to the systems-of-record tables, not the vector store.
 */
@Service
public class CorpusIngestionService {

    /**
     * EmbeddingStoreIngestor.ingest() sends every segment from the given
     * documents as a single unbatched embedAll() call with no internal
     * chunking (verified against langchain4j 1.18.0 source). At corpus
     * scale (100+ docs, 600+ chunks) this one giant request reliably
     * overwhelmed the local Ollama embedding runner ("connection refused"
     * on an internal /tokenize call) even though the same 38-doc/230-chunk
     * corpus ingested fine as one batch. Batching by document count keeps
     * each embedAll() call small regardless of total corpus size.
     */
    private static final int DOCUMENTS_PER_BATCH = 10;

    private final EmbeddingModel embeddingModel;
    private final EmbeddingStore<TextSegment> embeddingStore;
    private final Path corpusPath;

    public CorpusIngestionService(
            EmbeddingModel embeddingModel,
            EmbeddingStore<TextSegment> embeddingStore,
            @Value("${rag.ingest.corpus-path}") String corpusPath) {
        this.embeddingModel = embeddingModel;
        this.embeddingStore = embeddingStore;
        this.corpusPath = Path.of(corpusPath);
    }

    /** Wipes and reseeds the declared test-data/ corpus. Never called at arbitrary runtime — only via the reset endpoint. */
    public IngestionSummary reset() {
        embeddingStore.removeAll();

        if (!Files.isDirectory(corpusPath)) {
            return new IngestionSummary(0, corpusPath.toString());
        }

        List<Document> documents = loadDocumentsWithSourceMetadata();

        if (!documents.isEmpty()) {
            DocumentSplitter splitter = DocumentSplitters.recursive(500, 50);
            EmbeddingStoreIngestor ingestor = EmbeddingStoreIngestor.builder()
                    .documentSplitter(splitter)
                    .embeddingModel(embeddingModel)
                    .embeddingStore(embeddingStore)
                    .build();
            for (int start = 0; start < documents.size(); start += DOCUMENTS_PER_BATCH) {
                int end = Math.min(start + DOCUMENTS_PER_BATCH, documents.size());
                ingestor.ingest(documents.subList(start, end));
            }
        }

        return new IngestionSummary(documents.size(), corpusPath.toString());
    }

    /**
     * Loads each file individually (rather than FileSystemDocumentLoader.loadDocumentsRecursively
     * in one call) so "department" and "source" metadata can be set directly from the filesystem
     * path, not from whatever the loader happens to populate automatically -- confirmed empirically
     * (a direct query against pgvector's stored metadata) that loadDocumentsRecursively's documents
     * only carried absolute_directory_path, never file_name, so citations had no document identity
     * to show the chatbot's source-of-truth citation. department = immediate parent folder name
     * (test-data/documents/<DEPT>/file.ext convention); source = the bare filename.
     */
    private List<Document> loadDocumentsWithSourceMetadata() {
        List<Document> documents = new ArrayList<>();
        try (Stream<Path> paths = Files.walk(corpusPath)) {
            List<Path> files = paths.filter(Files::isRegularFile).toList();
            for (Path file : files) {
                Document document = FileSystemDocumentLoader.loadDocument(file, new ApacheTikaDocumentParser());
                document.metadata().put("source", file.getFileName().toString());
                if (file.getParent() != null) {
                    document.metadata().put("department", file.getParent().getFileName().toString());
                }
                documents.add(document);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return documents;
    }
}
