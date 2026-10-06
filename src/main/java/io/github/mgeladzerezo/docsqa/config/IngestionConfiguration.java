package io.github.mgeladzerezo.docsqa.config;

import io.github.mgeladzerezo.docsqa.document.FileStorage;
import io.github.mgeladzerezo.docsqa.embedding.EmbeddingGateway;
import io.github.mgeladzerezo.docsqa.embedding.EmbeddingProfileGuard;
import io.github.mgeladzerezo.docsqa.ingest.Chunker;
import io.github.mgeladzerezo.docsqa.ingest.IngestionPipeline;
import io.github.mgeladzerezo.docsqa.ingest.IngestionRepository;
import io.github.mgeladzerezo.docsqa.ingest.IngestionWorker;
import io.github.mgeladzerezo.docsqa.ingest.PdfTextExtractor;
import io.github.mgeladzerezo.docsqa.ingest.TextAssembler;
import io.github.mgeladzerezo.docsqa.ingest.TokenEstimator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Assembles the ingestion pipeline from its plain-Java parts and the configured limits. */
@Configuration
public class IngestionConfiguration {

    @Bean
    PdfTextExtractor pdfTextExtractor(AppProperties properties) {
        return new PdfTextExtractor(properties.upload().maxPages(), properties.ingestion().minCharsPerPage());
    }

    @Bean
    TextAssembler textAssembler() {
        return new TextAssembler();
    }

    @Bean
    Chunker chunker(AppProperties properties) {
        AppProperties.Chunking chunking = properties.chunking();
        return new Chunker(
                chunking.targetTokens(),
                chunking.maxTokens(),
                chunking.overlapTokens(),
                chunking.minTokens(),
                TokenEstimator.HEURISTIC);
    }

    @Bean
    IngestionPipeline ingestionPipeline(
            IngestionRepository repository,
            FileStorage storage,
            PdfTextExtractor extractor,
            TextAssembler assembler,
            Chunker chunker,
            EmbeddingGateway embeddings,
            AppProperties properties) {
        AppProperties.Ingestion ingestion = properties.ingestion();
        return new IngestionPipeline(repository, storage, extractor, assembler, chunker, embeddings,
                new IngestionPipeline.Settings(
                        ingestion.lease(), ingestion.maxAttempts(), ingestion.retryBackoff(), ingestion.embeddingBatchSize()));
    }

    /**
     * The guard parameter is not used; depending on it makes Spring verify the embedding model against
     * the stored data before the first worker thread can claim a document.
     */
    @Bean
    IngestionWorker ingestionWorker(
            IngestionRepository repository,
            IngestionPipeline pipeline,
            AppProperties properties,
            EmbeddingProfileGuard guard) {
        AppProperties.Ingestion ingestion = properties.ingestion();
        return new IngestionWorker(repository, pipeline, ingestion.workers(), ingestion.lease(), ingestion.pollInterval());
    }
}
