package io.github.mgeladzerezo.docsqa.ingest;

import io.github.mgeladzerezo.docsqa.document.DocumentRecord;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

/**
 * A fixed pool of threads that pull documents from the database queue. Each thread takes a new
 * document only after finishing the previous one, so the number of documents in memory is bounded by
 * the pool size no matter how many are uploaded; the rest wait as rows.
 *
 * <p>Nothing about a job lives in this object. Killing the JVM at any point loses no work that was
 * committed, and the next start (or another instance) resumes the document once its lease runs out.
 */
public class IngestionWorker implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(IngestionWorker.class);

    private final IngestionRepository repository;
    private final IngestionPipeline pipeline;
    private final int threads;
    private final Duration lease;
    private final Duration pollInterval;
    private final String instanceId = UUID.randomUUID().toString().substring(0, 8);
    private final List<Thread> running = new ArrayList<>();
    /** Released by uploads so an idle worker starts immediately instead of at its next poll. */
    private final Semaphore wakeUps = new Semaphore(0);
    private volatile boolean active;

    public IngestionWorker(
            IngestionRepository repository, IngestionPipeline pipeline, int threads, Duration lease, Duration pollInterval) {
        this.repository = repository;
        this.pipeline = pipeline;
        this.threads = threads;
        this.lease = lease;
        this.pollInterval = pollInterval;
    }

    @Override
    public synchronized void start() {
        if (active) {
            return;
        }
        active = true;
        for (int i = 0; i < threads; i++) {
            String workerId = instanceId + "-" + i;
            Thread thread = Thread.ofPlatform().name("ingest-" + workerId).daemon(true).start(() -> run(workerId));
            running.add(thread);
        }
        log.info("Started {} ingestion worker thread(s), instance {}", threads, instanceId);
    }

    @Override
    public synchronized void stop() {
        active = false;
        running.forEach(Thread::interrupt);
        for (Thread thread : running) {
            try {
                thread.join(TimeUnit.SECONDS.toMillis(10));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        running.clear();
    }

    @Override
    public boolean isRunning() {
        return active;
    }

    /** Hints that new work was queued. Safe to call from any thread, any number of times. */
    public void wakeUp() {
        if (wakeUps.availablePermits() < threads) {
            wakeUps.release();
        }
    }

    private void run(String workerId) {
        while (active) {
            try {
                Optional<DocumentRecord> claimed = repository.claim(workerId, lease);
                if (claimed.isPresent()) {
                    pipeline.process(claimed.get(), workerId);
                } else {
                    wakeUps.tryAcquire(pollInterval.toMillis(), TimeUnit.MILLISECONDS);
                }
            } catch (InterruptedException e) {
                // Shutdown. A document in progress keeps its lease and is resumed after it expires.
                return;
            } catch (RuntimeException e) {
                log.error("Ingestion worker {} hit an unexpected error; backing off", workerId, e);
                try {
                    Thread.sleep(pollInterval);
                } catch (InterruptedException interrupted) {
                    return;
                }
            }
        }
    }
}
