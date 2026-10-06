package io.github.mgeladzerezo.docsqa.answer;

import io.github.mgeladzerezo.docsqa.retrieval.RetrievedChunk;
import java.util.UUID;

/**
 * A retrieved passage as it is numbered for one answer. The number is what a citation such as
 * {@code [2]} refers to; the offsets locate the passage in the document text.
 *
 * @param n 1-based source number, unique within one answer
 */
public record Source(
        int n,
        UUID documentId,
        String filename,
        String heading,
        int pageStart,
        int pageEnd,
        int charStart,
        int charEnd,
        String text,
        double similarity,
        Integer vectorRank,
        Integer keywordRank,
        double score) {

    static Source of(int n, RetrievedChunk chunk) {
        return new Source(n, chunk.documentId(), chunk.filename(), chunk.heading(), chunk.pageStart(), chunk.pageEnd(),
                chunk.charStart(), chunk.charEnd(), chunk.content(), chunk.similarity(), chunk.vectorRank(),
                chunk.keywordRank(), chunk.score());
    }
}
