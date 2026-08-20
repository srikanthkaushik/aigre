package com.aigre.ingestion;

import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.DocumentSplitter;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.document.loader.FileSystemDocumentLoader;
import dev.langchain4j.data.document.parser.apache.tika.ApacheTikaDocumentParser;
import dev.langchain4j.data.document.splitter.DocumentSplitters;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
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

    /**
     * DOCUMENTS_PER_BATCH assumed roughly-uniform, small documents -- true for the original
     * curated corpus, but department onboarding can introduce a single much larger document (a
     * full statute/regulation text, not a short SOP). Confirmed live: a single 222KB .txt file
     * split into ~493 chunks, all landing in one embedAll() call regardless of the 10-document
     * cap, reproducing the exact "one giant request overwhelms the local Ollama embedding runner"
     * failure this class's own DOCUMENTS_PER_BATCH comment already documents -- just triggered by
     * one oversized document instead of total corpus size. Segments are additionally sub-batched
     * by this count immediately before each embedAll() call so no single HTTP request to the
     * embedding model exceeds it, independent of how many documents or how large any one of them is.
     */
    private static final int SEGMENTS_PER_EMBED_CALL = 50;

    /**
     * Fix for the "cross-reference-competition" retrieval bug (PROJECT.md): several corpus docs
     * deliberately contain a disambiguation sentence naming a DIFFERENT department/topic ("that's
     * a DPW matter, not illegal dumping"), per plan.md's own "cross-references between documents"
     * realism requirement. A 500-char chunk consisting mostly of that one sentence can score as
     * relevant to the OTHER topic as the correct document itself, sometimes outranking it. Two
     * prior fix attempts (an elaborate rerank prompt; a dedicated ONNX cross-encoder) were tried
     * and reverted -- see RetrievalService's history. This is the corpus-restructuring option
     * (option a) instead: authors wrap a disambiguating clause in [[XREF]]...[[/XREF]] inline in
     * the source .txt.
     *
     * A chunk needs up to THREE representations, not two -- an early version of this fix only
     * stripped the embedded text and left it there, which turned out insufficient: the store is
     * HYBRID search (vector + Postgres FTS) over the STORED text, and RetrievalService's rerank
     * step -- which actually decides the final top-1, since all initialK candidates get reranked
     * and sorted by rerankScore -- also scored the stored text. Leaving disambiguation content in
     * the stored text (deliberately, so the answering LLM still sees it once a chunk is genuinely
     * retrieved) meant FTS and the reranker were both still swayed by it, unfixed. So: EMBEDDED
     * text has marked spans removed (for vector similarity); a "rerank_text" metadata field, same
     * stripped content, lets RetrievalService's LLM rerank step score the same clean version FTS
     * can't see through; STORED/returned text keeps the full original prose (markers stripped,
     * content intact) for the final answer-generation context and anything shown to a citizen.
     */
    private static final Pattern XREF_SPAN = Pattern.compile("\\[\\[XREF]](.*?)\\[\\[/XREF]]", Pattern.DOTALL);
    private static final Pattern XREF_MARKERS = Pattern.compile("\\[\\[/?XREF]]");

    /**
     * A chunk that's mostly (or entirely) a marked disambiguation span has little or nothing left
     * to embed once that span is removed -- skipped from the vector index entirely rather than
     * indexed on a near-empty vector. Its content isn't lost: the same prose typically also
     * appears in a neighboring chunk of the same document (500-char window, 50-char overlap) or
     * survives intact in this chunk's own stored text when the marked span is only part of it.
     */
    private static final int MIN_EMBEDDABLE_CHARS = 20;

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
            for (int start = 0; start < documents.size(); start += DOCUMENTS_PER_BATCH) {
                int end = Math.min(start + DOCUMENTS_PER_BATCH, documents.size());
                ingestBatch(documents.subList(start, end), splitter);
            }
        }

        return new IngestionSummary(documents.size(), corpusPath.toString());
    }

    /**
     * Bypasses EmbeddingStoreIngestor (which embeds and stores the exact same text by
     * construction, with no interception point) so the embedded and stored text can differ --
     * see the XREF_SPAN javadoc above. splitAll()/embedAll()/addAll() keep the same one-call-per-
     * batch shape the ingestor provided, so this isn't a step backwards on the Ollama-overwhelm
     * problem the batching above already solved.
     */
    private void ingestBatch(List<Document> batch, DocumentSplitter splitter) {
        List<TextSegment> rawSegments = splitter.splitAll(batch);

        List<TextSegment> embeddableSegments = new ArrayList<>();
        List<TextSegment> storedSegments = new ArrayList<>();
        for (TextSegment segment : rawSegments) {
            String embedText = XREF_SPAN.matcher(segment.text()).replaceAll(" ").trim();
            if (embedText.length() < MIN_EMBEDDABLE_CHARS) {
                continue;
            }
            String storedText = XREF_MARKERS.matcher(segment.text()).replaceAll("");
            Metadata storedMetadata = segment.metadata().copy().put("rerank_text", embedText);

            embeddableSegments.add(TextSegment.from(embedText, segment.metadata()));
            storedSegments.add(TextSegment.from(storedText, storedMetadata));
        }

        for (int start = 0; start < embeddableSegments.size(); start += SEGMENTS_PER_EMBED_CALL) {
            int end = Math.min(start + SEGMENTS_PER_EMBED_CALL, embeddableSegments.size());
            List<Embedding> embeddings =
                    embeddingModel.embedAll(embeddableSegments.subList(start, end)).content();
            embeddingStore.addAll(embeddings, storedSegments.subList(start, end));
        }
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
