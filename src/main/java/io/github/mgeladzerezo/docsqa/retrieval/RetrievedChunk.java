package io.github.mgeladzerezo.docsqa.retrieval;

import com.fasterxml.jackson.annotation.JsonIgnore;
import java.util.UUID;

/**
 * A chunk returned by a search, with everything needed to cite it and to explain why it was found.
 *
 * @param vectorRank   1-based position among the nearest neighbours, or null if it was not one of them
 * @param keywordRank  1-based position in the full-text ranking, or null if no query term matched
 * @param keywordScore {@code ts_rank_cd} of the full-text match, or null
 * @param similarity   cosine similarity between the query and the chunk, computed for every row
 * @param score        reciprocal-rank-fusion score the result list is ordered by
 * @param embedding    the stored vector, used for MMR and never serialised
 */
public record RetrievedChunk(
        long chunkId,
        UUID documentId,
        String filename,
        String collection,
        int ordinal,
        String heading,
        String content,
        int pageStart,
        int pageEnd,
        int charStart,
        int charEnd,
        Integer vectorRank,
        Integer keywordRank,
        Double keywordScore,
        double similarity,
        double score,
        @JsonIgnore float[] embedding) {
}
