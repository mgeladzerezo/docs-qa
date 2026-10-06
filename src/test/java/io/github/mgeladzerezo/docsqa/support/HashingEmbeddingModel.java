package io.github.mgeladzerezo.docsqa.support;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;

/**
 * A deterministic stand-in for a real embedding model: a bag of words hashed into 384 dimensions.
 * Texts that share words get similar vectors, which is enough to test ranking, filtering and fusion
 * without downloading a model, and the same text always gets the same vector.
 */
public class HashingEmbeddingModel implements EmbeddingModel {

    public static final int DIMENSIONS = 384;

    private final AtomicInteger calls = new AtomicInteger();
    private final AtomicInteger textsEmbedded = new AtomicInteger();

    /** Number of {@link #call} invocations so far, i.e. batches. */
    public int calls() {
        return calls.get();
    }

    public int textsEmbedded() {
        return textsEmbedded.get();
    }

    @Override
    public EmbeddingResponse call(EmbeddingRequest request) {
        calls.incrementAndGet();
        List<Embedding> embeddings = new ArrayList<>();
        for (String text : request.getInstructions()) {
            textsEmbedded.incrementAndGet();
            embeddings.add(new Embedding(vectorOf(text), embeddings.size()));
        }
        return new EmbeddingResponse(embeddings);
    }

    @Override
    public float[] embed(Document document) {
        return vectorOf(document.getText());
    }

    @Override
    public int dimensions() {
        return DIMENSIONS;
    }

    public static float[] vectorOf(String text) {
        float[] vector = new float[DIMENSIONS];
        for (String word : text.toLowerCase(Locale.ROOT).split("[^a-z0-9]+")) {
            if (word.length() < 2) {
                continue;
            }
            int hash = word.hashCode();
            vector[Math.floorMod(hash, DIMENSIONS)] += (hash >>> 31) == 0 ? 1f : -1f;
        }
        return vector;
    }
}
