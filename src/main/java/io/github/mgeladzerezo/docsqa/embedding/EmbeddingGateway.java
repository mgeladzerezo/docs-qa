package io.github.mgeladzerezo.docsqa.embedding;

import java.util.List;
import java.util.concurrent.Semaphore;
import org.springframework.ai.embedding.EmbeddingModel;

/**
 * The only place the application talks to the {@link EmbeddingModel}. It gives the model an identity
 * (name and dimension, stored with every document), checks every vector against that identity and
 * bounds how many ingestion batches run inside the model at once.
 *
 * <p>Vectors are normalised to unit length before they leave this class. Cosine distance does not
 * care, but it makes "cosine similarity" and "dot product" the same number everywhere downstream.
 */
public class EmbeddingGateway {

    private final EmbeddingModel model;
    private final String modelName;
    private final int dimension;
    private final Semaphore batchPermits;

    /**
     * @param maxConcurrentBatches ingestion batches allowed inside the model at the same time; the
     *                             in-JVM model is CPU-bound, so more permits than cores only adds contention
     */
    public EmbeddingGateway(EmbeddingModel model, String modelName, int dimension, int maxConcurrentBatches) {
        this.model = model;
        this.modelName = modelName;
        this.dimension = dimension;
        this.batchPermits = new Semaphore(maxConcurrentBatches, true);
    }

    public String modelName() {
        return modelName;
    }

    public int dimension() {
        return dimension;
    }

    /**
     * Embeds a batch of passages for ingestion. Blocks while all permits are taken, which is the
     * backpressure between ingestion workers and the model: workers wait here instead of queueing
     * unbounded work inside it.
     */
    public List<float[]> embedPassages(List<String> texts) throws InterruptedException {
        batchPermits.acquire();
        try {
            return checked(model.embed(texts), texts.size());
        } finally {
            batchPermits.release();
        }
    }

    /** Embeds a search query. Queries are small and latency-sensitive, so they do not wait for a permit. */
    public float[] embedQuery(String text) {
        return checked(List.of(model.embed(text)), 1).getFirst();
    }

    private List<float[]> checked(List<float[]> vectors, int expectedCount) {
        if (vectors.size() != expectedCount) {
            throw new IllegalStateException("Embedding model %s returned %d vectors for %d inputs"
                    .formatted(modelName, vectors.size(), expectedCount));
        }
        for (float[] vector : vectors) {
            if (vector.length != dimension) {
                throw new IllegalStateException(
                        "Embedding model %s returned %d dimensions but docsqa.embedding.dimension is %d"
                                .formatted(modelName, vector.length, dimension));
            }
            Vectors.normalize(vector);
        }
        return vectors;
    }
}
