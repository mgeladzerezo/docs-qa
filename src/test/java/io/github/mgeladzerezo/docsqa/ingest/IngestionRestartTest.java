package io.github.mgeladzerezo.docsqa.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import io.github.mgeladzerezo.docsqa.document.DocumentRecord;
import io.github.mgeladzerezo.docsqa.document.DocumentRepository;
import io.github.mgeladzerezo.docsqa.document.DocumentService;
import io.github.mgeladzerezo.docsqa.document.DocumentService.BacklogFullException;
import io.github.mgeladzerezo.docsqa.document.DocumentStatus;
import io.github.mgeladzerezo.docsqa.document.FileStorage;
import io.github.mgeladzerezo.docsqa.embedding.EmbeddingGateway;
import io.github.mgeladzerezo.docsqa.ingest.IngestionRepository.LeaseLostException;
import io.github.mgeladzerezo.docsqa.sample.CorpusDocument;
import io.github.mgeladzerezo.docsqa.sample.CorpusPdfRenderer;
import io.github.mgeladzerezo.docsqa.support.FakeEmbeddingConfiguration;
import io.github.mgeladzerezo.docsqa.support.HashingEmbeddingModel;
import io.github.mgeladzerezo.docsqa.support.PostgresTest;
import java.io.ByteArrayInputStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Restart safety of ingestion. The application context runs with zero worker threads, so each test
 * plays the workers itself and can stop one at an exact point, which is what a crash looks like from
 * the database's side: a lease that is never renewed.
 */
@SpringBootTest(properties = {"docsqa.ingestion.workers=0", "docsqa.ingestion.max-backlog=6"})
@Import(FakeEmbeddingConfiguration.class)
class IngestionRestartTest extends PostgresTest {

    private static final Duration LEASE = Duration.ofMillis(600);
    private static final int BATCH = 3;

    @Autowired
    DocumentService service;
    @Autowired
    DocumentRepository documents;
    @Autowired
    IngestionRepository repository;
    @Autowired
    FileStorage storage;
    @Autowired
    PdfTextExtractor extractor;
    @Autowired
    TextAssembler assembler;
    @Autowired
    Chunker chunker;
    @Autowired
    JdbcClient jdbc;

    private final ExecutorService background = Executors.newCachedThreadPool();

    @BeforeEach
    void emptyLibrary() {
        jdbc.sql("TRUNCATE document CASCADE").update();
    }

    @AfterEach
    void stopBackgroundThreads() {
        background.shutdownNow();
    }

    @Test
    void aDocumentWhoseWorkerDiedMidEmbeddingIsFinishedByAnotherWithoutRedoingCommittedBatches() throws Exception {
        UUID id = uploadSample(0);
        // Worker A commits two batches, then never returns from the model: the process is gone.
        StallingModel stalling = new StallingModel(2);
        DocumentRecord claimedByA = repository.claim("worker-a", LEASE).orElseThrow();
        background.submit(() -> {
            pipeline(stalling).process(claimedByA, "worker-a");
            return null;
        });
        assertThat(stalling.stalled.await(10, TimeUnit.SECONDS)).isTrue();

        DocumentRecord abandoned = documents.findById(id).orElseThrow();
        assertThat(abandoned.status()).isEqualTo(DocumentStatus.EMBEDDING);
        assertThat(abandoned.chunksEmbedded()).isEqualTo(2 * BATCH);
        int total = abandoned.chunksTotal();
        assertThat(total).isGreaterThan(3 * BATCH);
        assertThat(repository.claim("worker-b", LEASE)).as("the lease is still held").isEmpty();

        // After the lease expires, worker B takes over and embeds only what is missing.
        HashingEmbeddingModel counting = new HashingEmbeddingModel();
        DocumentRecord claimedByB = awaitClaim("worker-b");
        assertThat(claimedByB.id()).isEqualTo(id);
        assertThat(claimedByB.attempts()).isEqualTo(2);
        pipeline(counting).process(claimedByB, "worker-b");

        DocumentRecord ready = documents.findById(id).orElseThrow();
        assertThat(ready.status()).isEqualTo(DocumentStatus.READY);
        assertThat(ready.chunksEmbedded()).isEqualTo(total);
        assertThat(counting.textsEmbedded()).as("committed batches are not recomputed").isEqualTo(total - 2 * BATCH);

        // Worker A comes back to life. Its write is fenced off, so its stale vectors never land.
        stalling.resume.countDown();
        await().atMost(Duration.ofSeconds(10)).until(() -> stalling.finishedCalls.get() == 3);
        Thread.sleep(300);
        assertThat(documents.findById(id).orElseThrow().status()).isEqualTo(DocumentStatus.READY);
        assertThat(jdbc.sql("SELECT count(*) FROM chunk WHERE embedding IS NULL").query(Long.class).single()).isZero();
        assertThat(chunksWithVector(StallingModel.STALE_VECTOR)).as("vectors written by the fenced worker").isZero();
    }

    @Test
    void aCrashBeforeTheChunksWereStoredRedoesExtractionFromTheFile() throws Exception {
        UUID id = uploadSample(1);
        assertThat(repository.claim("worker-a", LEASE)).isPresent();
        repository.advance(id, "worker-a", DocumentStatus.EXTRACTING, LEASE);
        // Worker A dies here: stage EXTRACTING, nothing stored.

        DocumentRecord claimedByB = awaitClaim("worker-b");
        assertThat(claimedByB.status()).isEqualTo(DocumentStatus.EXTRACTING);
        pipeline(new HashingEmbeddingModel()).process(claimedByB, "worker-b");

        DocumentRecord ready = documents.findById(id).orElseThrow();
        assertThat(ready.status()).isEqualTo(DocumentStatus.READY);
        assertThat(ready.chunksTotal()).isPositive().isEqualTo(ready.chunksEmbedded());
        assertThat(ready.pageCount()).isEqualTo(CorpusDocument.loadBundled().get(1).pages().size());
        // The dead worker's writes are rejected from now on.
        assertThatThrownBy(() -> repository.advance(id, "worker-a", DocumentStatus.CHUNKING, LEASE))
                .isInstanceOf(LeaseLostException.class);
    }

    @Test
    void concurrentWorkersNeverClaimTheSameDocument() throws Exception {
        List<UUID> uploaded = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            uploaded.add(uploadSample(i));
        }
        Set<UUID> claimed = ConcurrentHashMap.newKeySet();
        AtomicInteger duplicates = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        List<java.util.concurrent.Future<?>> workers = new ArrayList<>();
        for (int w = 0; w < 8; w++) {
            String workerId = "worker-" + w;
            workers.add(background.submit(() -> {
                start.await();
                Optional<DocumentRecord> document;
                while ((document = repository.claim(workerId, Duration.ofMinutes(1))).isPresent()) {
                    if (!claimed.add(document.get().id())) {
                        duplicates.incrementAndGet();
                    }
                }
                return null;
            }));
        }
        start.countDown();
        for (var worker : workers) {
            worker.get(20, TimeUnit.SECONDS);
        }

        assertThat(duplicates).hasValue(0);
        assertThat(claimed).containsExactlyInAnyOrderElementsOf(uploaded);
    }

    @Test
    void aTransientFailureIsRetriedAfterABackoffAndGivenUpAfterTheAttemptLimit() throws Exception {
        UUID id = uploadSample(2);
        EmbeddingModel broken = failing("embedding backend unreachable");

        DocumentRecord first = repository.claim("worker-a", LEASE).orElseThrow();
        pipeline(broken).process(first, "worker-a");

        DocumentRecord waiting = documents.findById(id).orElseThrow();
        assertThat(waiting.status()).as("stage is kept for the retry").isEqualTo(DocumentStatus.EMBEDDING);
        assertThat(waiting.failureReason()).isEqualTo("Attempt 1 failed, retrying: embedding backend unreachable");
        assertThat(repository.claim("worker-a", LEASE)).as("not claimable during the backoff").isEmpty();

        pipeline(broken).process(awaitClaim("worker-a"), "worker-a");
        assertThat(documents.findById(id).orElseThrow().status()).isEqualTo(DocumentStatus.EMBEDDING);

        DocumentRecord third = awaitClaim("worker-a");
        assertThat(third.attempts()).isEqualTo(3);
        pipeline(broken).process(third, "worker-a");

        DocumentRecord failed = documents.findById(id).orElseThrow();
        assertThat(failed.status()).isEqualTo(DocumentStatus.FAILED);
        assertThat(failed.failureReason()).isEqualTo("Failed after 3 attempts. Last error: embedding backend unreachable");
        assertThat(jdbc.sql("SELECT count(*) FROM chunk").query(Long.class).single()).as("partial chunks are removed").isZero();
        Thread.sleep(LEASE.toMillis());
        assertThat(repository.claim("worker-a", LEASE)).as("a failed document is not picked up again").isEmpty();
    }

    @Test
    void aTransientFailureThatRecoversEndsInReady() throws Exception {
        UUID id = uploadSample(3);
        pipeline(failing("temporary outage")).process(repository.claim("worker-a", LEASE).orElseThrow(), "worker-a");

        pipeline(new HashingEmbeddingModel()).process(awaitClaim("worker-a"), "worker-a");

        DocumentRecord ready = documents.findById(id).orElseThrow();
        assertThat(ready.status()).isEqualTo(DocumentStatus.READY);
        assertThat(ready.failureReason()).isNull();
    }

    @Test
    void aDocumentThatKeepsKillingItsWorkerIsGivenUpInsteadOfLoopingForever() throws Exception {
        UUID id = uploadSample(0);
        for (int attempt = 1; attempt <= 3; attempt++) {
            assertThat(awaitClaim("worker-" + attempt).attempts()).isEqualTo(attempt);
            // The worker dies without reporting anything.
        }

        DocumentRecord fourth = awaitClaim("worker-4");
        pipeline(new HashingEmbeddingModel()).process(fourth, "worker-4");

        DocumentRecord failed = documents.findById(id).orElseThrow();
        assertThat(failed.status()).isEqualTo(DocumentStatus.FAILED);
        assertThat(failed.failureReason()).startsWith("Gave up after 3 attempts");
    }

    @Test
    void aGracefulShutdownHandsTheDocumentBackImmediatelyWithoutSpendingAnAttempt() throws Exception {
        UUID id = uploadSample(1);
        StallingModel stalling = new StallingModel(1);
        IngestionWorker worker = new IngestionWorker(
                repository, pipeline(stalling), 1, Duration.ofMinutes(5), Duration.ofMillis(50));
        worker.start();
        assertThat(stalling.stalled.await(10, TimeUnit.SECONDS)).isTrue();

        worker.stop();

        DocumentRecord released = documents.findById(id).orElseThrow();
        assertThat(released.status()).isEqualTo(DocumentStatus.EMBEDDING);
        assertThat(released.attempts()).isZero();
        assertThat(released.chunksEmbedded()).isEqualTo(BATCH);
        // No waiting for the five-minute lease: the next worker can continue right away.
        DocumentRecord resumed = repository.claim("next-start", LEASE).orElseThrow();
        HashingEmbeddingModel counting = new HashingEmbeddingModel();
        pipeline(counting).process(resumed, "next-start");
        assertThat(documents.findById(id).orElseThrow().status()).isEqualTo(DocumentStatus.READY);
        assertThat(counting.textsEmbedded()).isEqualTo(released.chunksTotal() - BATCH);
    }

    @Test
    void uploadsAreRefusedWhileTheBacklogIsFull() {
        for (int i = 0; i < 4; i++) {
            uploadSample(i);
        }
        service.upload("extra-1.pdf", new ByteArrayInputStream(TestPdfs.ofLines(List.of(List.of("One more document with a line of text.")))), null, false);
        service.upload("extra-2.pdf", new ByteArrayInputStream(TestPdfs.ofLines(List.of(List.of("And another document with a line of text.")))), null, false);

        assertThatThrownBy(() -> service.upload("extra-3.pdf",
                new ByteArrayInputStream(TestPdfs.ofLines(List.of(List.of("This one does not fit into the backlog.")))), null, false))
                .isInstanceOf(BacklogFullException.class)
                .hasMessageContaining("6 documents are still being ingested");
    }

    // ---------------------------------------------------------------------------------------------

    private UUID uploadSample(int index) {
        CorpusDocument source = CorpusDocument.loadBundled().get(index);
        return service.upload(source.fileName(), new ByteArrayInputStream(CorpusPdfRenderer.render(source)), null, false)
                .document().id();
    }

    private IngestionPipeline pipeline(EmbeddingModel model) {
        EmbeddingGateway gateway = new EmbeddingGateway(model, "test-hashing-384", HashingEmbeddingModel.DIMENSIONS, 1);
        return new IngestionPipeline(repository, storage, extractor, assembler, chunker, gateway,
                new IngestionPipeline.Settings(LEASE, 3, Duration.ofMillis(300), BATCH));
    }

    private DocumentRecord awaitClaim(String workerId) {
        return await().atMost(Duration.ofSeconds(10)).pollInterval(Duration.ofMillis(50))
                .until(() -> repository.claim(workerId, LEASE), Optional::isPresent)
                .orElseThrow();
    }

    private long chunksWithVector(float[] vector) {
        float[] normalized = io.github.mgeladzerezo.docsqa.embedding.Vectors.normalize(vector.clone());
        return jdbc.sql("SELECT count(*) FROM chunk WHERE embedding = CAST(:vector AS vector)")
                .param("vector", io.github.mgeladzerezo.docsqa.embedding.Vectors.toLiteral(normalized))
                .query(Long.class)
                .single();
    }

    private static EmbeddingModel failing(String message) {
        return new HashingEmbeddingModel() {
            @Override
            public EmbeddingResponse call(EmbeddingRequest request) {
                throw new IllegalStateException(message);
            }
        };
    }

    /**
     * Answers {@code healthyCalls} batches normally and then blocks inside the model until resumed
     * (or interrupted), returning a recognisable vector for the batch it was stuck on.
     */
    private static final class StallingModel extends HashingEmbeddingModel {

        static final float[] STALE_VECTOR = stale();

        final CountDownLatch stalled = new CountDownLatch(1);
        final CountDownLatch resume = new CountDownLatch(1);
        final AtomicInteger finishedCalls = new AtomicInteger();
        private final int healthyCalls;
        private final AtomicInteger started = new AtomicInteger();

        StallingModel(int healthyCalls) {
            this.healthyCalls = healthyCalls;
        }

        @Override
        public EmbeddingResponse call(EmbeddingRequest request) {
            try {
                if (started.incrementAndGet() <= healthyCalls) {
                    return super.call(request);
                }
                stalled.countDown();
                try {
                    resume.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("interrupted inside the model", e);
                }
                List<org.springframework.ai.embedding.Embedding> stale = new ArrayList<>();
                for (int i = 0; i < request.getInstructions().size(); i++) {
                    stale.add(new org.springframework.ai.embedding.Embedding(STALE_VECTOR.clone(), i));
                }
                return new EmbeddingResponse(stale);
            } finally {
                finishedCalls.incrementAndGet();
            }
        }

        private static float[] stale() {
            float[] vector = new float[DIMENSIONS];
            Arrays.fill(vector, 1f);
            return vector;
        }
    }
}
