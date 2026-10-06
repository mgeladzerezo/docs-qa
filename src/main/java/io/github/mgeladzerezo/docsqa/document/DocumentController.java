package io.github.mgeladzerezo.docsqa.document;

import io.github.mgeladzerezo.docsqa.document.DocumentService.UploadResult;
import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

/** REST API of the document library. */
@RestController
@RequestMapping("/api/documents")
public class DocumentController {

    private final DocumentService service;
    private final FileStorage storage;

    public DocumentController(DocumentService service, FileStorage storage) {
        this.service = service;
        this.storage = storage;
    }

    /**
     * What clients see of a document. {@code chunksEmbedded}/{@code chunksTotal} is the "embedding n/m"
     * progress; {@code failureReason} is set for FAILED and while a failed attempt is waiting for retry.
     */
    public record DocumentView(
            UUID id,
            String filename,
            String collection,
            long sizeBytes,
            Integer pageCount,
            DocumentStatus status,
            String failureReason,
            int chunksTotal,
            int chunksEmbedded,
            int attempts,
            boolean sample,
            String embeddingModel,
            Instant createdAt,
            Instant updatedAt) {

        static DocumentView of(DocumentRecord record) {
            return new DocumentView(record.id(), record.filename(), record.collection(), record.sizeBytes(),
                    record.pageCount(), record.status(), record.failureReason(), record.chunksTotal(),
                    record.chunksEmbedded(), record.attempts(), record.sample(), record.embeddingModel(),
                    record.createdAt(), record.updatedAt());
        }
    }

    public record UploadResponse(DocumentView document, boolean duplicate) {
    }

    public record PageView(UUID documentId, String filename, int pageNumber, int pageCount, int charStart, String text) {
    }

    @GetMapping
    public List<DocumentView> list() {
        return service.list().stream().map(DocumentView::of).toList();
    }

    @GetMapping("/{id}")
    public DocumentView get(@PathVariable UUID id) {
        return DocumentView.of(require(id));
    }

    /** 202 for a newly queued document, 200 when the same file was already in the library. */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<UploadResponse> upload(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "collection", required = false) String collection) throws IOException {
        try (InputStream in = file.getInputStream()) {
            UploadResult result = service.upload(file.getOriginalFilename(), in, collection, false);
            return ResponseEntity.status(result.duplicate() ? HttpStatus.OK : HttpStatus.ACCEPTED)
                    .body(new UploadResponse(DocumentView.of(result.document()), result.duplicate()));
        }
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        if (!service.delete(id)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No such document");
        }
        return ResponseEntity.noContent().build();
    }

    /** The cleaned text of one page, the coordinate system citations are expressed in. */
    @GetMapping("/{id}/pages/{pageNumber}")
    public PageView page(@PathVariable UUID id, @PathVariable int pageNumber) {
        DocumentRecord document = require(id);
        PageRecord page = service.page(id, pageNumber)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such page"));
        return new PageView(id, document.filename(), page.pageNumber(),
                document.pageCount() == null ? 0 : document.pageCount(), page.charStart(), page.content());
    }

    @GetMapping("/{id}/file")
    public ResponseEntity<Resource> file(@PathVariable UUID id) {
        DocumentRecord document = require(id);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.inline().filename(document.filename(), java.nio.charset.StandardCharsets.UTF_8).build().toString())
                .header("X-Content-Type-Options", "nosniff")
                .body(new FileSystemResource(storage.resolve(document.storagePath())));
    }

    private DocumentRecord require(UUID id) {
        return service.find(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such document"));
    }
}
