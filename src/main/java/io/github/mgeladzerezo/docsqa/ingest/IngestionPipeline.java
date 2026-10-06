package io.github.mgeladzerezo.docsqa.ingest;

import io.github.mgeladzerezo.docsqa.document.DocumentRecord;
import io.github.mgeladzerezo.docsqa.document.DocumentStatus;
import io.github.mgeladzerezo.docsqa.document.FileStorage;
import io.github.mgeladzerezo.docsqa.embedding.EmbeddingGateway;
import io.github.mgeladzerezo.docsqa.ingest.IngestionRepository.LeaseLostException;
import io.github.mgeladzerezo.docsqa.ingest.IngestionRepository.PendingChunk;
import java.time.Duration;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Processes one claimed document from whatever stage it is in to READY or FAILED.
 *
 * <p>The pipeline is resumable because each stage leaves durable, self-describing state:
 * <ol>
 *   <li><b>Extract and chunk</b> are pure functions of the stored file. Their result is written in one
 *       transaction that also flips the stage to EMBEDDING, so they are either fully done or redone.</li>
 *   <li><b>Embed</b> works through {@code chunk} rows whose {@code embedding IS NULL}, one batch per
 *       transaction. A worker that takes over simply continues with the rows that are still NULL;
 *       finished batches are never recomputed.</li>
 * </ol>
 */
public class IngestionPipeline {

    private static final Logger log = LoggerFactory.getLogger(IngestionPipeline.class);
    private static final int MAX_REASON_LENGTH = 500;

    /**
     * @param lease        extended by every successful write
     * @param maxAttempts  claims after which a transiently failing document is given up
     * @param retryBackoff pause before a failed attempt is retried
     * @param batchSize    chunks per embedding call and per commit
     */
    public record Settings(Duration lease, int maxAttempts, Duration retryBackoff, int batchSize) {
    }

    private final IngestionRepository repository;
    private final FileStorage storage;
    private final PdfTextExtractor extractor;
    private final TextAssembler assembler;
    private final Chunker chunker;
    private final EmbeddingGateway embeddings;
    private final Settings settings;

    public IngestionPipeline(
            IngestionRepository repository,
            FileStorage storage,
            PdfTextExtractor extractor,
            TextAssembler assembler,
            Chunker chunker,
            EmbeddingGateway embeddings,
            Settings settings) {
        this.repository = repository;
        this.storage = storage;
        this.extractor = extractor;
        this.assembler = assembler;
        this.chunker = chunker;
        this.embeddings = embeddings;
        this.settings = settings;
    }

    /**
     * Runs the document to completion. Never throws for a problem with the document itself: the
     * outcome is recorded on the row. Only an interrupt (shutdown) propagates.
     *
     * @param document the row as returned by {@link IngestionRepository#claim}
     */
    public void process(DocumentRecord document, String workerId) throws InterruptedException {
        try {
            if (document.attempts() > settings.maxAttempts()) {
                // Reached only when earlier attempts ended without reporting, i.e. the worker died.
                repository.markFailed(document.id(), workerId,
                        "Gave up after %d attempts that did not finish; the last one may have crashed the worker."
                                .formatted(settings.maxAttempts()));
                return;
            }
            if (!document.embeddingModel().equals(embeddings.modelName())
                    || document.embeddingDimension() != embeddings.dimension()) {
                throw new IngestionException(
                        "The document was queued for embedding model %s (%d) but this service runs %s (%d)."
                                .formatted(document.embeddingModel(), document.embeddingDimension(),
                                        embeddings.modelName(), embeddings.dimension()));
            }
            if (document.status() != DocumentStatus.EMBEDDING) {
                extractAndChunk(document, workerId);
            }
            embedRemaining(document, workerId);
            repository.markReady(document.id(), workerId);
            log.info("Document {} ({}) is ready", document.id(), document.filename());
        } catch (InterruptedException e) {
            releaseOnShutdown(document, workerId);
            throw e;
        } catch (LeaseLostException e) {
            log.warn("{}; abandoning it to the current owner", e.getMessage());
        } catch (IngestionException e) {
            fail(document, workerId, e.getMessage());
        } catch (RuntimeException e) {
            if (Thread.currentThread().isInterrupted()) {
                // Shutdown surfaced as a failed blocking call, not as a problem with the document.
                releaseOnShutdown(document, workerId);
                throw new InterruptedException();
            }
            retryOrFail(document, workerId, e);
        }
    }

    private void extractAndChunk(DocumentRecord document, String workerId) {
        repository.advance(document.id(), workerId, DocumentStatus.EXTRACTING, settings.lease());
        ExtractedDocument extracted = assembler.assemble(extractor.extract(storage.resolve(document.storagePath())));
        repository.advance(document.id(), workerId, DocumentStatus.CHUNKING, settings.lease());
        List<Chunk> chunks = chunker.chunk(extracted);
        if (chunks.isEmpty()) {
            throw new IngestionException("No text could be extracted from the PDF.");
        }
        repository.storeExtraction(document.id(), workerId, extracted, chunks, settings.lease());
    }

    private void embedRemaining(DocumentRecord document, String workerId) throws InterruptedException {
        while (true) {
            List<PendingChunk> batch = repository.nextPendingChunks(document.id(), settings.batchSize());
            if (batch.isEmpty()) {
                return;
            }
            List<String> texts = batch.stream()
                    .map(chunk -> Chunk.embeddingText(chunk.heading(), chunk.content()))
                    .toList();
            List<float[]> vectors = embeddings.embedPassages(texts);
            repository.storeEmbeddings(document.id(), workerId, batch, vectors, settings.lease());
        }
    }

    /** The database call needs an uninterrupted thread, so the flag is cleared for its duration. */
    private void releaseOnShutdown(DocumentRecord document, String workerId) {
        Thread.interrupted();
        try {
            repository.release(document.id(), workerId);
        } catch (RuntimeException e) {
            log.warn("Could not release document {} on shutdown; it resumes when its lease expires", document.id(), e);
        } finally {
            Thread.currentThread().interrupt();
        }
    }

    private void retryOrFail(DocumentRecord document, String workerId, RuntimeException error) {
        String reason = describe(error);
        if (document.attempts() >= settings.maxAttempts()) {
            log.error("Document {} failed on attempt {} and will not be retried", document.id(), document.attempts(), error);
            fail(document, workerId, "Failed after %d attempts. Last error: %s".formatted(document.attempts(), reason));
            return;
        }
        log.warn("Document {} failed on attempt {} of {}; retrying in {}",
                document.id(), document.attempts(), settings.maxAttempts(), settings.retryBackoff(), error);
        try {
            repository.releaseForRetry(document.id(), workerId,
                    "Attempt %d failed, retrying: %s".formatted(document.attempts(), reason), settings.retryBackoff());
        } catch (LeaseLostException e) {
            log.warn("{}; not scheduling a retry", e.getMessage());
        }
    }

    private void fail(DocumentRecord document, String workerId, String reason) {
        try {
            repository.markFailed(document.id(), workerId, reason);
            log.info("Document {} ({}) failed: {}", document.id(), document.filename(), reason);
        } catch (LeaseLostException e) {
            log.warn("{}; not recording the failure", e.getMessage());
        }
    }

    private static String describe(Throwable error) {
        String message = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
        return message.length() <= MAX_REASON_LENGTH ? message : message.substring(0, MAX_REASON_LENGTH) + "...";
    }
}
