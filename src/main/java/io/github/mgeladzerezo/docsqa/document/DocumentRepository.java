package io.github.mgeladzerezo.docsqa.document;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Reads and writes of the document library that are not part of the ingestion job protocol. */
@Repository
public class DocumentRepository {

    public static final RowMapper<DocumentRecord> ROW_MAPPER = DocumentRepository::mapRow;

    private final JdbcClient jdbc;

    public DocumentRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Inserts a new document in state QUEUED. Fails with a duplicate-key error if the hash exists. */
    public void insertQueued(
            UUID id,
            String filename,
            String collection,
            String contentHash,
            long sizeBytes,
            String storagePath,
            String embeddingModel,
            int embeddingDimension,
            boolean sample) {
        jdbc.sql("""
                        INSERT INTO document (id, filename, collection, content_hash, size_bytes, storage_path,
                                              status, embedding_model, embedding_dimension, sample)
                        VALUES (:id, :filename, :collection, :hash, :size, :path, 'QUEUED', :model, :dimension, :sample)
                        """)
                .param("id", id)
                .param("filename", filename)
                .param("collection", collection)
                .param("hash", contentHash)
                .param("size", sizeBytes)
                .param("path", storagePath)
                .param("model", embeddingModel)
                .param("dimension", embeddingDimension)
                .param("sample", sample)
                .update();
    }

    public Optional<DocumentRecord> findById(UUID id) {
        return jdbc.sql("SELECT * FROM document WHERE id = :id").param("id", id).query(ROW_MAPPER).optional();
    }

    public Optional<DocumentRecord> findByHash(String contentHash) {
        return jdbc.sql("SELECT * FROM document WHERE content_hash = :hash")
                .param("hash", contentHash)
                .query(ROW_MAPPER)
                .optional();
    }

    public List<DocumentRecord> findAll() {
        return jdbc.sql("SELECT * FROM document ORDER BY created_at, filename").query(ROW_MAPPER).list();
    }

    public long count() {
        return jdbc.sql("SELECT count(*) FROM document").query(Long.class).single();
    }

    /** Documents that are queued or being processed: the ingestion backlog. */
    public long countInFlight() {
        return jdbc.sql("SELECT count(*) FROM document WHERE status NOT IN ('READY', 'FAILED')")
                .query(Long.class)
                .single();
    }

    /** Puts a FAILED document back into the queue with a fresh attempt budget. */
    public boolean requeueFailed(UUID id) {
        return jdbc.sql("""
                        UPDATE document
                        SET status = 'QUEUED', failure_reason = NULL, attempts = 0,
                            locked_by = NULL, locked_until = NULL, updated_at = now()
                        WHERE id = :id AND status = 'FAILED'
                        """)
                .param("id", id)
                .update() == 1;
    }

    /** Deletes the document; pages and chunks go with it through the foreign keys. */
    public boolean delete(UUID id) {
        return jdbc.sql("DELETE FROM document WHERE id = :id").param("id", id).update() == 1;
    }

    public Optional<PageRecord> findPage(UUID documentId, int pageNumber) {
        return jdbc.sql("""
                        SELECT page_number, char_start, content FROM document_page
                        WHERE document_id = :id AND page_number = :page
                        """)
                .param("id", documentId)
                .param("page", pageNumber)
                .query((rs, _) -> new PageRecord(rs.getInt("page_number"), rs.getInt("char_start"), rs.getString("content")))
                .optional();
    }

    /** The distinct (model, dimension) pairs present in the library, used to refuse a mixed corpus. */
    public List<EmbeddingProfile> embeddingProfiles() {
        return jdbc.sql("SELECT DISTINCT embedding_model, embedding_dimension FROM document ORDER BY 1, 2")
                .query((rs, _) -> new EmbeddingProfile(rs.getString(1), rs.getInt(2)))
                .list();
    }

    /** The dimension n of the {@code chunk.embedding vector(n)} column. */
    public int embeddingColumnDimension() {
        return jdbc.sql("""
                        SELECT atttypmod FROM pg_attribute
                        WHERE attrelid = 'chunk'::regclass AND attname = 'embedding'
                        """)
                .query(Integer.class)
                .single();
    }

    public record EmbeddingProfile(String model, int dimension) {
    }

    private static DocumentRecord mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new DocumentRecord(
                rs.getObject("id", UUID.class),
                rs.getString("filename"),
                rs.getString("collection"),
                rs.getString("content_hash"),
                rs.getLong("size_bytes"),
                rs.getString("storage_path"),
                rs.getObject("page_count", Integer.class),
                DocumentStatus.valueOf(rs.getString("status")),
                rs.getString("failure_reason"),
                rs.getInt("chunks_total"),
                rs.getInt("chunks_embedded"),
                rs.getString("embedding_model"),
                rs.getInt("embedding_dimension"),
                rs.getInt("attempts"),
                rs.getBoolean("sample"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant());
    }
}
