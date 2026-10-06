package io.github.mgeladzerezo.docsqa.retrieval;

import io.github.mgeladzerezo.docsqa.embedding.Vectors;
import java.util.ArrayList;
import java.util.List;

/**
 * Maximal marginal relevance: picks passages one at a time, each time taking the candidate that is
 * most relevant <em>and</em> least similar to what has already been picked.
 *
 * <p>Chunks overlap by design and a fact is often restated in neighbouring paragraphs, so the top of
 * a pure relevance ranking tends to be several versions of the same passage. Sending those to the
 * model spends context on repetition and leaves out the second-best distinct passage.
 */
public final class MmrSelector {

    private MmrSelector() {
    }

    /**
     * @param candidates ordered by relevance, best first; relevance is {@code score} relative to the best
     * @param k          how many to pick
     * @param lambda     weight of relevance against redundancy: 1.0 keeps the input order, 0.0 only
     *                   maximises diversity
     * @return the picked candidates in pick order
     */
    public static List<RetrievedChunk> select(List<RetrievedChunk> candidates, int k, double lambda) {
        List<RetrievedChunk> remaining = new ArrayList<>(candidates);
        List<RetrievedChunk> selected = new ArrayList<>();
        if (remaining.isEmpty()) {
            return selected;
        }
        double bestScore = remaining.getFirst().score();
        while (selected.size() < k && !remaining.isEmpty()) {
            int bestIndex = 0;
            double bestValue = Double.NEGATIVE_INFINITY;
            for (int i = 0; i < remaining.size(); i++) {
                RetrievedChunk candidate = remaining.get(i);
                double relevance = bestScore > 0 ? candidate.score() / bestScore : 0;
                double redundancy = 0;
                for (RetrievedChunk chosen : selected) {
                    redundancy = Math.max(redundancy, Vectors.cosine(candidate.embedding(), chosen.embedding()));
                }
                double value = lambda * relevance - (1 - lambda) * redundancy;
                // Strict '>' keeps the higher-ranked candidate on ties.
                if (value > bestValue) {
                    bestValue = value;
                    bestIndex = i;
                }
            }
            selected.add(remaining.remove(bestIndex));
        }
        return selected;
    }
}
