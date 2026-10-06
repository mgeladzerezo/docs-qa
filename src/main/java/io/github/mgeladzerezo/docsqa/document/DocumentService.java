package io.github.mgeladzerezo.docsqa.document;

import io.github.mgeladzerezo.docsqa.config.AppProperties;
import io.github.mgeladzerezo.docsqa.embedding.EmbeddingGateway;
import io.github.mgeladzerezo.docsqa.ingest.IngestionWorker;
import java.io.InputStream;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

/** The document library: accepting uploads, listing, reading pages and deleting. */
@Service
public class DocumentService {

    public static final String DEFAULT_COLLECTION = "default";
    private static final Pattern COLLECTION_NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_-]{0,63}");
    private static final int MAX_FILENAME_LENGTH = 200;

    private final DocumentRepository documents;
    private final FileStorage storage;
    private final EmbeddingGateway embeddings;
    private final IngestionWorker worker;
    private final AppProperties properties;

    public DocumentService(
            DocumentRepository documents,
            FileStorage storage,
            EmbeddingGateway embeddings,
            IngestionWorker worker,
            AppProperties properties) {
        this.documents = documents;
        this.storage = storage;
        this.embeddings = embeddings;
        this.worker = worker;
        this.properties = properties;
    }

    /** @param duplicate true when the same bytes were already in the library and no new document was created */
    public record UploadResult(DocumentRecord document, boolean duplicate) {
    }

    /**
     * Accepts a PDF and queues it for ingestion. Uploading bytes that are already in the library
     * returns the existing document; if that document had failed, it is queued again.
     *
     * @throws BacklogFullException              if too many documents are still being ingested
     * @throws FileStorage.FileTooLargeException if the file exceeds the size limit
     * @throws InvalidUploadException            if the content is not a PDF or the collection name is invalid
     */
    public UploadResult upload(String originalFilename, InputStream content, String collection, boolean sample) {
        String targetCollection = collection == null || collection.isBlank() ? DEFAULT_COLLECTION : collection.strip();
        if (!COLLECTION_NAME.matcher(targetCollection).matches()) {
            throw new InvalidUploadException("A collection name may contain letters, digits, '-' and '_' (at most 64 characters).");
        }
        long backlog = documents.countInFlight();
        if (backlog >= properties.ingestion().maxBacklog()) {
            throw new BacklogFullException(backlog);
        }
        FileStorage.Received received = storage.receive(content, properties.upload().maxFileSize().toBytes());
        if (!received.looksLikePdf()) {
            storage.discard(received);
            throw new InvalidUploadException("The file is not a PDF (it does not start with the %PDF- signature).");
        }
        Optional<DocumentRecord> existing = documents.findByHash(received.sha256());
        if (existing.isPresent()) {
            storage.discard(received);
            return new UploadResult(requeueIfFailed(existing.get()), true);
        }
        UUID id = UUID.randomUUID();
        String storagePath = storage.commit(received);
        try {
            documents.insertQueued(id, cleanFilename(originalFilename), targetCollection, received.sha256(),
                    received.size(), storagePath, embeddings.modelName(), embeddings.dimension(), sample);
        } catch (DuplicateKeyException e) {
            // Two uploads of the same file raced; the unique index on content_hash picked the winner.
            return new UploadResult(documents.findByHash(received.sha256()).orElseThrow(), true);
        }
        worker.wakeUp();
        return new UploadResult(documents.findById(id).orElseThrow(), false);
    }

    public List<DocumentRecord> list() {
        return documents.findAll();
    }

    public Optional<DocumentRecord> find(UUID id) {
        return documents.findById(id);
    }

    public Optional<PageRecord> page(UUID id, int pageNumber) {
        return documents.findPage(id, pageNumber);
    }

    public boolean delete(UUID id) {
        Optional<DocumentRecord> document = documents.findById(id);
        if (document.isEmpty() || !documents.delete(id)) {
            return false;
        }
        storage.delete(document.get().storagePath());
        return true;
    }

    private DocumentRecord requeueIfFailed(DocumentRecord document) {
        if (document.status() == DocumentStatus.FAILED && documents.requeueFailed(document.id())) {
            worker.wakeUp();
            return documents.findById(document.id()).orElseThrow();
        }
        return document;
    }

    /** Keeps only the last path segment and printable characters; the name is display-only. */
    static String cleanFilename(String original) {
        String name = original == null ? "" : original.replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1).replaceAll("\\p{Cntrl}", "").strip();
        if (name.isEmpty()) {
            name = "document.pdf";
        }
        return name.length() <= MAX_FILENAME_LENGTH ? name : name.substring(0, MAX_FILENAME_LENGTH);
    }

    /** The ingestion queue is at capacity; the client should retry later. */
    public static class BacklogFullException extends RuntimeException {

        public BacklogFullException(long backlog) {
            super("%d documents are still being ingested. Try again when some have finished.".formatted(backlog));
        }
    }

    /** The upload was understood but is not acceptable. */
    public static class InvalidUploadException extends RuntimeException {

        public InvalidUploadException(String message) {
            super(message);
        }
    }
}
