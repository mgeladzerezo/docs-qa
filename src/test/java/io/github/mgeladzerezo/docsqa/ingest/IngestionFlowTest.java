package io.github.mgeladzerezo.docsqa.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import io.github.mgeladzerezo.docsqa.document.DocumentRecord;
import io.github.mgeladzerezo.docsqa.document.DocumentService;
import io.github.mgeladzerezo.docsqa.document.DocumentService.InvalidUploadException;
import io.github.mgeladzerezo.docsqa.document.DocumentService.UploadResult;
import io.github.mgeladzerezo.docsqa.document.DocumentStatus;
import io.github.mgeladzerezo.docsqa.document.FileStorage;
import io.github.mgeladzerezo.docsqa.document.FileStorage.FileTooLargeException;
import io.github.mgeladzerezo.docsqa.document.PageRecord;
import io.github.mgeladzerezo.docsqa.sample.CorpusDocument;
import io.github.mgeladzerezo.docsqa.sample.CorpusDocument.Element;
import io.github.mgeladzerezo.docsqa.sample.CorpusPdfRenderer;
import io.github.mgeladzerezo.docsqa.support.FakeEmbeddingConfiguration;
import io.github.mgeladzerezo.docsqa.support.PostgresTest;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Upload to READY through the real worker threads, against real pgvector: what is stored, what is
 * rejected and how duplicates behave.
 */
@SpringBootTest(properties = "docsqa.upload.max-file-size=300KB")
@Import(FakeEmbeddingConfiguration.class)
class IngestionFlowTest extends PostgresTest {

    @Autowired
    DocumentService service;
    @Autowired
    FileStorage storage;
    @Autowired
    JdbcClient jdbc;

    @BeforeEach
    void emptyLibrary() {
        jdbc.sql("TRUNCATE document CASCADE").update();
    }

    @Test
    void anUploadedPdfBecomesReadyWithPagesChunksAndEmbeddingsStored() {
        CorpusDocument source = CorpusDocument.loadBundled().getFirst();

        UploadResult upload = service.upload(source.fileName(), pdf(source), "policies", false);

        assertThat(upload.duplicate()).isFalse();
        assertThat(upload.document().status()).isEqualTo(DocumentStatus.QUEUED);
        DocumentRecord ready = awaitTerminal(upload.document().id());
        assertThat(ready.status()).isEqualTo(DocumentStatus.READY);
        assertThat(ready.failureReason()).isNull();
        assertThat(ready.pageCount()).isEqualTo(source.pages().size());
        assertThat(ready.collection()).isEqualTo("policies");
        assertThat(ready.embeddingModel()).isEqualTo("test-hashing-384");
        assertThat(ready.chunksTotal()).isGreaterThan(5).isEqualTo(ready.chunksEmbedded());

        long chunkRows = jdbc.sql("SELECT count(*) FROM chunk WHERE document_id = :id AND embedding IS NOT NULL")
                .param("id", ready.id()).query(Long.class).single();
        assertThat(chunkRows).isEqualTo(ready.chunksTotal());

        // Stored page text is the source page, and every chunk is a substring of the pages it claims.
        for (int p = 0; p < source.pages().size(); p++) {
            String expected = source.pages().get(p).stream().map(Element::text).collect(Collectors.joining("\n\n"));
            assertThat(service.page(ready.id(), p + 1).orElseThrow().content()).isEqualTo(expected);
        }
        List<StoredChunk> chunks = jdbc.sql("""
                        SELECT content, char_start, char_end, page_start, page_end, heading
                        FROM chunk WHERE document_id = :id ORDER BY ordinal
                        """)
                .param("id", ready.id()).query(StoredChunk.class).list();
        for (StoredChunk chunk : chunks) {
            // The coordinate system citations rely on: chunk offset minus page offset is the position in the page.
            PageRecord page = service.page(ready.id(), chunk.pageStart()).orElseThrow();
            int offsetInPage = chunk.charStart() - page.charStart();
            String partOnPage = page.content().substring(
                    offsetInPage, Math.min(page.content().length(), offsetInPage + chunk.content().length()));
            assertThat(partOnPage).isNotEmpty();
            assertThat(chunk.content()).startsWith(partOnPage);
            assertThat(chunk.charEnd() - chunk.charStart()).isEqualTo(chunk.content().length());
            assertThat(chunk.heading()).isNotBlank();
        }
    }

    @Test
    void uploadingTheSameBytesAgainReturnsTheExistingDocument() {
        CorpusDocument source = CorpusDocument.loadBundled().get(1);
        UploadResult first = service.upload("manual.pdf", pdf(source), null, false);
        awaitTerminal(first.document().id());

        UploadResult second = service.upload("same-file-other-name.pdf", pdf(source), null, false);

        assertThat(second.duplicate()).isTrue();
        assertThat(second.document().id()).isEqualTo(first.document().id());
        assertThat(second.document().filename()).isEqualTo("manual.pdf");
        assertThat(service.list()).hasSize(1);
        assertThat(jdbc.sql("SELECT count(DISTINCT document_id) FROM chunk").query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void aScannedPdfFailsWithAReasonAndCanBeRetriedByUploadingAgain() {
        byte[] scan = TestPdfs.scanned(2);

        UploadResult upload = service.upload("scan.pdf", new ByteArrayInputStream(scan), null, false);
        DocumentRecord failed = awaitTerminal(upload.document().id());

        assertThat(failed.status()).isEqualTo(DocumentStatus.FAILED);
        assertThat(failed.failureReason()).contains("No text layer", "OCR is out of scope");
        assertThat(failed.attempts()).as("a scanned PDF is not retried").isEqualTo(1);
        assertThat(jdbc.sql("SELECT count(*) FROM chunk").query(Long.class).single()).isZero();

        UploadResult again = service.upload("scan.pdf", new ByteArrayInputStream(scan), null, false);
        assertThat(again.duplicate()).isTrue();
        assertThat(again.document().id()).isEqualTo(failed.id());
        assertThat(awaitTerminal(failed.id()).status()).isEqualTo(DocumentStatus.FAILED);
    }

    @Test
    void aFileThatIsNotAPdfIsRejectedAtUploadAndLeavesNothingBehind() throws Exception {
        long filesBefore = filesInStorage();

        assertThatThrownBy(() -> service.upload("notes.pdf",
                new ByteArrayInputStream("just some text".getBytes(StandardCharsets.UTF_8)), null, false))
                .isInstanceOf(InvalidUploadException.class)
                .hasMessageContaining("not a PDF");

        assertThat(service.list()).isEmpty();
        assertThat(filesInStorage()).isEqualTo(filesBefore);
    }

    @Test
    void aFileOverTheSizeLimitIsRejectedWhileStreaming() throws Exception {
        long filesBefore = filesInStorage();
        byte[] big = new byte[400 * 1024];
        System.arraycopy("%PDF-1.7".getBytes(StandardCharsets.US_ASCII), 0, big, 0, 8);

        assertThatThrownBy(() -> service.upload("big.pdf", new ByteArrayInputStream(big), null, false))
                .isInstanceOf(FileTooLargeException.class);

        assertThat(service.list()).isEmpty();
        assertThat(filesInStorage()).isEqualTo(filesBefore);
    }

    @Test
    void invalidCollectionNamesAreRejected() {
        assertThatThrownBy(() -> service.upload("a.pdf", pdf(CorpusDocument.loadBundled().getFirst()), "../etc", false))
                .isInstanceOf(InvalidUploadException.class);
    }

    @Test
    void deletingADocumentRemovesItsRowsAndItsFile() {
        CorpusDocument source = CorpusDocument.loadBundled().get(2);
        DocumentRecord ready = awaitTerminal(service.upload(source.fileName(), pdf(source), null, false).document().id());
        assertThat(Files.exists(storage.resolve(ready.storagePath()))).isTrue();

        assertThat(service.delete(ready.id())).isTrue();

        assertThat(service.find(ready.id())).isEmpty();
        assertThat(jdbc.sql("SELECT count(*) FROM chunk").query(Long.class).single()).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM document_page").query(Long.class).single()).isZero();
        assertThat(Files.exists(storage.resolve(ready.storagePath()))).isFalse();
        assertThat(service.delete(ready.id())).isFalse();
    }

    record StoredChunk(String content, int charStart, int charEnd, int pageStart, int pageEnd, String heading) {
    }

    private DocumentRecord awaitTerminal(UUID id) {
        return await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(50))
                .until(() -> service.find(id).orElseThrow(), document -> document.status().isTerminal());
    }

    private long filesInStorage() throws Exception {
        try (var files = Files.list(storage.resolve("."))) {
            return files.count();
        }
    }

    private static ByteArrayInputStream pdf(CorpusDocument source) {
        return new ByteArrayInputStream(CorpusPdfRenderer.render(source));
    }
}
