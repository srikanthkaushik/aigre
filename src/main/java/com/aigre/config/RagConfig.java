package com.aigre.config;

import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.ollama.OllamaEmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.pgvector.PgVectorEmbeddingStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;
import java.time.Duration;

@Configuration
public class RagConfig {

    @Bean
    public EmbeddingModel embeddingModel(
            @Value("${ollama.base-url}") String baseUrl,
            @Value("${ollama.embedding-model}") String modelName,
            @Value("${ollama.timeout:120s}") Duration timeout) {
        return OllamaEmbeddingModel.builder()
                .baseUrl(baseUrl)
                .modelName(modelName)
                .timeout(timeout)
                .build();
    }

    /**
     * SearchMode.HYBRID fuses vector cosine similarity with Postgres full-text search via
     * Reciprocal Rank Fusion — this is the "hybrid vector + FTS" retrieval layer required by
     * the day-one scaffold, built into langchain4j-pgvector rather than hand-rolled SQL.
     */
    @Bean
    public EmbeddingStore<TextSegment> embeddingStore(DataSource dataSource, EmbeddingModel embeddingModel) {
        return PgVectorEmbeddingStore.datasourceBuilder()
                .datasource(dataSource)
                .table("rag_documents")
                .dimension(embeddingModel.dimension())
                .createTable(true)
                .searchMode(PgVectorEmbeddingStore.SearchMode.HYBRID)
                .rrfK(60)
                .build();
    }
}
