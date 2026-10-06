package io.github.mgeladzerezo.docsqa.answer;

import java.util.List;

/**
 * What a client receives while a question is being answered, in this order: one {@link Sources},
 * any number of {@link Token}s, then exactly one {@link Done} or {@link Failure}. The record names in
 * lower case are the SSE event names.
 */
public sealed interface AnswerEvent {

    /** The SSE event name. */
    String name();

    /**
     * Sent before any answer text, so a client can render the sources while the answer streams.
     *
     * @param mode               {@code generative} (written by the language model) or {@code extractive}
     *                           (verbatim sentences, no language model)
     * @param standaloneQuestion the query retrieval ran with, after rewriting a follow-up
     * @param rewrite            how it was produced: {@code none}, {@code heuristic} or {@code model}
     * @param confident          false when the best passage is below the similarity threshold
     * @param candidates         the fused ranking before diversification; only when debug was requested
     */
    record Sources(
            String mode,
            String question,
            String standaloneQuestion,
            String rewrite,
            boolean confident,
            double bestSimilarity,
            double minSimilarity,
            List<Source> sources,
            List<Candidate> candidates) implements AnswerEvent {

        @Override
        public String name() {
            return "sources";
        }
    }

    /**
     * One row of the retrieval debug view.
     *
     * @param selectedAs the source number this chunk got, or null if MMR did not pick it
     */
    record Candidate(
            long chunkId,
            String filename,
            String heading,
            int pageStart,
            int pageEnd,
            Integer vectorRank,
            Integer keywordRank,
            Double keywordScore,
            double similarity,
            double score,
            Integer selectedAs,
            String preview) {
    }

    /** A piece of answer text. Unverified: citation markers in it have not been checked yet. */
    record Token(String text) implements AnswerEvent {

        @Override
        public String name() {
            return "token";
        }
    }

    /**
     * The final, verified answer. Clients should replace the streamed text with {@code answer}.
     *
     * @param answer           the answer with every citation marker that did not match a source removed
     * @param notFound         the documents do not contain an answer
     * @param invalidCitations source numbers the model cited that were never supplied
     * @param uncited          a generated answer that makes claims without a single valid citation
     * @param usage            token usage reported by the model; null for extractive answers
     * @param stopReason       the model's stop reason (for example {@code end_turn}, {@code max_tokens},
     *                         {@code refusal}); null for extractive answers
     */
    record Done(
            String mode,
            String answer,
            boolean notFound,
            List<Citation> citations,
            List<Integer> invalidCitations,
            boolean uncited,
            Usage usage,
            String stopReason,
            String model) implements AnswerEvent {

        @Override
        public String name() {
            return "done";
        }
    }

    record Usage(Integer inputTokens, Integer outputTokens) {
    }

    /** The answer could not be completed. Named "error" on the wire. */
    record Failure(String message) implements AnswerEvent {

        @Override
        public String name() {
            return "error";
        }
    }
}
