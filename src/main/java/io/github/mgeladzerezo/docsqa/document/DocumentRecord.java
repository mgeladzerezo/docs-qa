package io.github.mgeladzerezo.docsqa.document;

import java.time.Instant;
import java.util.UUID;

/**
 * A row of the {@code document} table.
 *
 * @param contentHash        SHA-256 of the file bytes, the de-duplication key
 * @param storagePath        file name inside the storage directory
 * @param pageCount          null until extraction has run
 * @param failureReason      why a FAILED document failed, or the last transient error of one being retried
 * @param embeddingModel     the model the chunks are (or will be) embedded with
 * @param attempts           how many times a worker has claimed the document
 * @param sample             true for the generated demo corpus
 */
public record DocumentRecord(
        UUID id,
        String filename,
        String collection,
        String contentHash,
        long sizeBytes,
        String storagePath,
        Integer pageCount,
        DocumentStatus status,
        String failureReason,
        int chunksTotal,
        int chunksEmbedded,
        String embeddingModel,
        int embeddingDimension,
        int attempts,
        boolean sample,
        Instant createdAt,
        Instant updatedAt) {
}
