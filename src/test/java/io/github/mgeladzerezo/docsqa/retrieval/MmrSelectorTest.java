package io.github.mgeladzerezo.docsqa.retrieval;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class MmrSelectorTest {

    @Test
    void aNearDuplicateOfAPickedPassageLosesToALessRelevantButDifferentOne() {
        RetrievedChunk best = chunk(1, 0.030, 1f, 0f);
        RetrievedChunk duplicate = chunk(2, 0.029, 0.99f, 0.05f);
        RetrievedChunk different = chunk(3, 0.020, 0f, 1f);

        List<RetrievedChunk> picked = MmrSelector.select(List.of(best, duplicate, different), 2, 0.7);

        assertThat(picked).extracting(RetrievedChunk::chunkId).containsExactly(1L, 3L);
    }

    @Test
    void lambdaOneKeepsTheRelevanceOrder() {
        List<RetrievedChunk> candidates = List.of(chunk(1, 0.030, 1f, 0f), chunk(2, 0.029, 1f, 0f), chunk(3, 0.020, 0f, 1f));

        assertThat(MmrSelector.select(candidates, 3, 1.0)).extracting(RetrievedChunk::chunkId).containsExactly(1L, 2L, 3L);
    }

    @Test
    void theMostRelevantCandidateIsAlwaysPickedFirstAndKIsRespected() {
        List<RetrievedChunk> candidates = List.of(chunk(1, 0.030, 1f, 0f), chunk(2, 0.010, 0f, 1f), chunk(3, 0.005, 1f, 1f));

        assertThat(MmrSelector.select(candidates, 1, 0.1)).extracting(RetrievedChunk::chunkId).containsExactly(1L);
        assertThat(MmrSelector.select(candidates, 10, 0.5)).hasSize(3);
        assertThat(MmrSelector.select(List.of(), 5, 0.5)).isEmpty();
    }

    private static RetrievedChunk chunk(long id, double score, float x, float y) {
        return new RetrievedChunk(id, UUID.randomUUID(), "f.pdf", "default", (int) id, null, "text " + id,
                1, 1, 0, 6, (int) id, null, null, 0.5, score, new float[] {x, y});
    }
}
