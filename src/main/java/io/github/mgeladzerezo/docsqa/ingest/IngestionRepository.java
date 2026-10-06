package io.github.mgeladzerezo.docsqa.ingest;

import io.github.mgeladzerezo.docsqa.document.DocumentRecord;
import io.github.mgeladzerezo.docsqa.document.DocumentRepository;
import io.github.mgeladzerezo.docsqa.document.DocumentStatus;
import io.github.mgeladzerezo.docsqa.embedding.Vectors;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The ingestion job protocol in SQL. A document row is its own job: {@code status} is the stage and
 * {@code locked_by}/{@code locked_until} are a lease.
 *
 * <p>Two rules make the protocol safe when workers crash, hang or run in several JVMs:
 * <ul>
 *   <li><b>Claiming</b> uses {@code FOR UPDATE SKIP LOCKED}, so two workers never take the same
 *       document, and treats an expired lease as free, so a document whose worker died is taken over.</li>
 *   <li><b>Every write is fenced</b>: it only succeeds while {@code locked_by} is still the writer.
 *       A worker that was presumed dead and wakes up after its document has been taken over fails its
 *       next write with {@link LeaseLostException} and its transaction rolls back.</li>
 * </ul>
 * Each successful write also extends the lease, so the lease only has to outlast the longest single
 * step, not the whole document.
 */
@Repository
public class IngestionRepository {

    private final JdbcClient jdbc;
    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactions;

    public IngestionRepository(JdbcClient jdbc, JdbcTemplate jdbcTemplate, TransactionTemplate transactions) {
        this.jdbc = jdbc;
        this.jdbcTemplate = jdbcTemplate;
        this.transactions = transactions;
    }

    /** A chunk whose embedding is still missing. */
    public record PendingChunk(long id, String heading, String content) {
    }

    /** The worker no longer owns the document; whatever it was doing must be abandoned. */
    public static class LeaseLostException extends RuntimeException {

        public LeaseLostException(UUID documentId, String workerId) {
            super("Worker %s no longer holds the lease on document %s".formatted(workerId, documentId));
        }
    }

    /**
     * Takes the oldest document that is unfinished and not leased, and leases it to {@code workerId}.
     * The attempt counter is incremented here, at the start of an attempt, so that an attempt which
     * kills the JVM still counts.
     */
    public Optional<DocumentRecord> claim(String workerId, Duration lease) {
        return jdbc.sql("""
                        UPDATE document
                        SET locked_by = :worker,
                            locked_until = now() + :leaseMs * interval '1 millisecond',
                            attempts = attempts + 1,
                            updated_at = now()
                        WHERE id = (
                            SELECT id FROM document
                            WHERE status IN ('QUEUED', 'EXTRACTING', 'CHUNKING', 'EMBEDDING')
                              AND (locked_until IS NULL OR locked_until < now())
                            ORDER BY created_at
                            FOR UPDATE SKIP LOCKED
                            LIMIT 1)
                        RETURNING *
                        """)
                .param("worker", workerId)
                .param("leaseMs", lease.toMillis())
                .query(DocumentRepository.ROW_MAPPER)
                .optional();
    }

    /** Moves the document to another in-flight stage. */
    public void advance(UUID id, String workerId, DocumentStatus status, Duration lease) {
        fenced(id, workerId, jdbc.sql("""
                        UPDATE document
                        SET status = :status, locked_until = now() + :leaseMs * interval '1 millisecond', updated_at = now()
                        WHERE id = :id AND locked_by = :worker
                        """)
                .param("status", status.name())
                .param("leaseMs", lease.toMillis())
                .param("id", id)
                .param("worker", workerId)
                .update());
    }

    /**
     * Replaces the document's pages and chunks and moves it to EMBEDDING, all in one transaction.
     * Because the stage only becomes EMBEDDING together with the complete chunk set, a worker that
     * finds a document in EMBEDDING can rely on the chunks being there and resume from them.
     */
    public void storeExtraction(UUID id, String workerId, ExtractedDocument document, List<Chunk> chunks, Duration lease) {
        transactions.executeWithoutResult(_ -> {
            jdbc.sql("DELETE FROM chunk WHERE document_id = :id").param("id", id).update();
            jdbc.sql("DELETE FROM document_page WHERE document_id = :id").param("id", id).update();
            jdbcTemplate.batchUpdate(
                    "INSERT INTO document_page (document_id, page_number, char_start, content) VALUES (?, ?, ?, ?)",
                    document.pages(),
                    200,
                    (statement, page) -> {
                        statement.setObject(1, id);
                        statement.setInt(2, page.pageNumber());
                        statement.setInt(3, page.start());
                        statement.setString(4, document.pageText(page));
                    });
            jdbcTemplate.batchUpdate("""
                            INSERT INTO chunk (document_id, ordinal, content, heading, page_start, page_end,
                                               char_start, char_end, token_count)
                            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                            """,
                    chunks,
                    200,
                    (statement, chunk) -> {
                        statement.setObject(1, id);
                        statement.setInt(2, chunk.ordinal());
                        statement.setString(3, chunk.content());
                        statement.setString(4, chunk.heading());
                        statement.setInt(5, chunk.pageStart());
                        statement.setInt(6, chunk.pageEnd());
                        statement.setInt(7, chunk.charStart());
                        statement.setInt(8, chunk.charEnd());
                        statement.setInt(9, chunk.tokenCount());
                    });
            fenced(id, workerId, jdbc.sql("""
                            UPDATE document
                            SET status = 'EMBEDDING', page_count = :pages, chunks_total = :chunks, chunks_embedded = 0,
                                locked_until = now() + :leaseMs * interval '1 millisecond', updated_at = now()
                            WHERE id = :id AND locked_by = :worker
                            """)
                    .param("pages", document.pages().size())
                    .param("chunks", chunks.size())
                    .param("leaseMs", lease.toMillis())
                    .param("id", id)
                    .param("worker", workerId)
                    .update());
        });
    }

    /** The next chunks without an embedding, in document order. An empty list means the document is done. */
    public List<PendingChunk> nextPendingChunks(UUID id, int limit) {
        return jdbc.sql("""
                        SELECT id, heading, content FROM chunk
                        WHERE document_id = :id AND embedding IS NULL
                        ORDER BY ordinal
                        LIMIT :limit
                        """)
                .param("id", id)
                .param("limit", limit)
                .query((rs, _) -> new PendingChunk(rs.getLong("id"), rs.getString("heading"), rs.getString("content")))
                .list();
    }

    /**
     * Writes one batch of embeddings and the new progress count atomically. If the lease was lost
     * while the model was computing, nothing is written.
     */
    public void storeEmbeddings(UUID id, String workerId, List<PendingChunk> chunks, List<float[]> vectors, Duration lease) {
        transactions.executeWithoutResult(_ -> {
            // Fence first: this takes the row lock on the document, so a concurrent takeover either
            // happened before (and we stop here) or waits until this batch has committed.
            fenced(id, workerId, jdbc.sql("""
                            UPDATE document
                            SET locked_until = now() + :leaseMs * interval '1 millisecond', updated_at = now()
                            WHERE id = :id AND locked_by = :worker
                            """)
                    .param("leaseMs", lease.toMillis())
                    .param("id", id)
                    .param("worker", workerId)
                    .update());
            for (int i = 0; i < chunks.size(); i++) {
                jdbc.sql("UPDATE chunk SET embedding = CAST(:vector AS vector) WHERE id = :id")
                        .param("vector", Vectors.toLiteral(vectors.get(i)))
                        .param("id", chunks.get(i).id())
                        .update();
            }
            jdbc.sql("""
                            UPDATE document
                            SET chunks_embedded = (SELECT count(*) FROM chunk WHERE document_id = :id AND embedding IS NOT NULL)
                            WHERE id = :id
                            """)
                    .param("id", id)
                    .update();
        });
    }

    public void markReady(UUID id, String workerId) {
        fenced(id, workerId, jdbc.sql("""
                        UPDATE document
                        SET status = 'READY', failure_reason = NULL, locked_by = NULL, locked_until = NULL, updated_at = now()
                        WHERE id = :id AND locked_by = :worker
                        """)
                .param("id", id)
                .param("worker", workerId)
                .update());
    }

    /** Ends ingestion for good and removes whatever partial pages and chunks exist. */
    public void markFailed(UUID id, String workerId, String reason) {
        transactions.executeWithoutResult(_ -> {
            fenced(id, workerId, jdbc.sql("""
                            UPDATE document
                            SET status = 'FAILED', failure_reason = :reason, chunks_total = 0, chunks_embedded = 0,
                                locked_by = NULL, locked_until = NULL, updated_at = now()
                            WHERE id = :id AND locked_by = :worker
                            """)
                    .param("reason", reason)
                    .param("id", id)
                    .param("worker", workerId)
                    .update());
            jdbc.sql("DELETE FROM chunk WHERE document_id = :id").param("id", id).update();
            jdbc.sql("DELETE FROM document_page WHERE document_id = :id").param("id", id).update();
        });
    }

    /**
     * Gives the document back after a failed attempt. It keeps its stage and stays invisible for
     * {@code backoff}, after which any worker may claim it again.
     */
    public void releaseForRetry(UUID id, String workerId, String reason, Duration backoff) {
        fenced(id, workerId, jdbc.sql("""
                        UPDATE document
                        SET failure_reason = :reason, locked_by = NULL,
                            locked_until = now() + :backoffMs * interval '1 millisecond', updated_at = now()
                        WHERE id = :id AND locked_by = :worker
                        """)
                .param("reason", reason)
                .param("backoffMs", backoff.toMillis())
                .param("id", id)
                .param("worker", workerId)
                .update());
    }

    /**
     * Hands the document back immediately and without spending an attempt. Used on a graceful
     * shutdown, so a restart continues at once instead of waiting for the lease to expire.
     */
    public void release(UUID id, String workerId) {
        jdbc.sql("""
                        UPDATE document
                        SET locked_by = NULL, locked_until = NULL, attempts = greatest(attempts - 1, 0), updated_at = now()
                        WHERE id = :id AND locked_by = :worker
                        """)
                .param("id", id)
                .param("worker", workerId)
                .update();
    }

    private static void fenced(UUID id, String workerId, int updatedRows) {
        if (updatedRows != 1) {
            throw new LeaseLostException(id, workerId);
        }
    }
}
