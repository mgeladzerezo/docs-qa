package io.github.mgeladzerezo.docsqa.config;

import java.nio.file.Path;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;

/**
 * All tunables of the service in one typed tree ({@code docsqa.*}). Defaults live in
 * {@code application.yml}; the reasons for the retrieval and chunking defaults are in the README.
 */
@ConfigurationProperties("docsqa")
public record AppProperties(
        Path storageDir,
        Upload upload,
        Ingestion ingestion,
        Chunking chunking,
        Embedding embedding,
        Retrieval retrieval,
        Answer answer,
        Anthropic anthropic,
        RateLimit rateLimit,
        SampleCorpus sampleCorpus) {

    /** Limits enforced when a PDF is uploaded and when it is opened for extraction. */
    public record Upload(DataSize maxFileSize, int maxPages) {
    }

    /**
     * @param workers            ingestion threads, each processing one document at a time
     * @param pollInterval       how long an idle worker sleeps before looking for work again
     * @param lease              how long a claimed document stays invisible to other workers; must exceed
     *                           the longest single step (extraction of one PDF or one embedding batch)
     * @param maxAttempts        claims after which a document is given up as FAILED
     * @param retryBackoff       delay before a document whose attempt failed transiently is retried
     * @param embeddingBatchSize chunks embedded and committed per step
     * @param maxConcurrentEmbeddingBatches permits shared by all workers for calls into the embedding model
     * @param maxBacklog         uploads are rejected with 429 while this many documents are not yet terminal
     * @param minCharsPerPage    below this average of extracted characters per page a PDF counts as scanned
     */
    public record Ingestion(
            int workers,
            Duration pollInterval,
            Duration lease,
            int maxAttempts,
            Duration retryBackoff,
            int embeddingBatchSize,
            int maxConcurrentEmbeddingBatches,
            int maxBacklog,
            int minCharsPerPage) {
    }

    /**
     * @param targetTokens  size a chunk is filled up to
     * @param maxTokens     hard ceiling; a chunk only exceeds the target to finish a paragraph
     * @param overlapTokens budget of trailing sentences repeated at the start of the next chunk
     * @param minTokens     a section tail smaller than this is merged into the previous chunk
     */
    public record Chunking(int targetTokens, int maxTokens, int overlapTokens, int minTokens) {
    }

    /**
     * @param provider       {@code onnx} builds the in-JVM model; any other value expects an
     *                       {@code EmbeddingModel} bean supplied by another Spring AI module
     * @param modelName      stored with every document; a corpus embedded with another name is refused
     * @param dimension      must match both the model output and the {@code vector(n)} column
     * @param cacheDir       where the ONNX model and tokenizer are cached after the first download
     * @param maxInputTokens word-piece limit passed to the tokenizer (the bundled default is 128)
     */
    public record Embedding(
            String provider,
            String modelName,
            int dimension,
            Path cacheDir,
            int maxInputTokens,
            String modelUri,
            String tokenizerUri) {
    }

    /**
     * @param candidates    rows taken from each of the vector and keyword rankings before fusion
     * @param fusedLimit    fused candidates handed to MMR
     * @param finalK        passages given to the answer stage
     * @param rrfK          the constant k of reciprocal rank fusion
     * @param mmrLambda     1.0 is pure relevance, 0.0 pure diversity
     * @param minSimilarity cosine similarity the best passage must reach, otherwise "not found"
     * @param efSearch      HNSW search breadth (pgvector {@code hnsw.ef_search})
     */
    public record Retrieval(
            int candidates,
            int fusedLimit,
            int finalK,
            int rrfK,
            double mmrLambda,
            double minSimilarity,
            int efSearch) {
    }

    /**
     * @param model            Anthropic model id used for answers and query rewriting
     * @param maxTokens        output ceiling per answer; it also has to cover adaptive thinking
     * @param effort           Anthropic {@code output_config.effort}: low, medium, high, xhigh or max
     * @param maxQuestionChars longer questions are rejected with 400
     * @param maxHistoryTurns  most recent conversation turns considered when rewriting a follow-up
     * @param streamTimeout    how long one SSE answer may take before the connection is closed
     */
    public record Answer(
            String model, int maxTokens, String effort, int maxQuestionChars, int maxHistoryTurns, Duration streamTimeout) {
    }

    /** @param apiKey empty means no-key mode: answers are extractive and no chat model bean exists */
    public record Anthropic(String apiKey) {
    }

    /** Requests per minute allowed per client address. */
    public record RateLimit(int chatPerMinute, int uploadPerMinute) {
    }

    /** @param preload ingest the generated sample PDFs on first start when the library is empty */
    public record SampleCorpus(boolean preload) {
    }
}
