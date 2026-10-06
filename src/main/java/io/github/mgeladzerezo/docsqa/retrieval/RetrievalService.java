package io.github.mgeladzerezo.docsqa.retrieval;

import io.github.mgeladzerezo.docsqa.config.AppProperties;
import io.github.mgeladzerezo.docsqa.embedding.EmbeddingGateway;
import io.github.mgeladzerezo.docsqa.embedding.EmbeddingProfileGuard;
import io.github.mgeladzerezo.docsqa.retrieval.SearchRepository.Filter;
import io.github.mgeladzerezo.docsqa.retrieval.SearchRepository.Mode;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * The retrieval pipeline: embed the query, run the hybrid search, diversify with MMR and decide
 * whether the result is good enough to answer from.
 */
@Service
public class RetrievalService {

    private final SearchRepository search;
    private final EmbeddingGateway embeddings;
    private final AppProperties.Retrieval settings;

    /** The guard parameter is unused; it orders this bean after the embedding model has been verified. */
    public RetrievalService(
            SearchRepository search, EmbeddingGateway embeddings, AppProperties properties, EmbeddingProfileGuard guard) {
        this.search = search;
        this.embeddings = embeddings;
        this.settings = properties.retrieval();
    }

    /**
     * @param candidates     the fused ranking before diversification, best first (shown by "retrieval debug")
     * @param selected       the passages chosen for the answer, in the order they are numbered as sources
     * @param bestSimilarity highest cosine similarity among the candidates, 0 if there are none
     * @param confident      false when {@code bestSimilarity} is below the configured threshold: the
     *                       corpus most likely does not cover the question and the answer is "not found"
     */
    public record Result(
            List<RetrievedChunk> candidates, List<RetrievedChunk> selected, double bestSimilarity, boolean confident) {
    }

    public Result retrieve(String query, Filter filter) {
        return retrieve(query, filter, Mode.HYBRID);
    }

    public Result retrieve(String query, Filter filter, Mode mode) {
        float[] queryVector = embeddings.embedQuery(query);
        List<RetrievedChunk> candidates = search.search(mode, query, queryVector, filter,
                settings.candidates(), settings.fusedLimit(), settings.rrfK(), settings.efSearch());
        List<RetrievedChunk> selected = MmrSelector.select(candidates, settings.finalK(), settings.mmrLambda());
        double bestSimilarity = candidates.stream().mapToDouble(RetrievedChunk::similarity).max().orElse(0);
        return new Result(candidates, selected, bestSimilarity, bestSimilarity >= settings.minSimilarity());
    }
}
