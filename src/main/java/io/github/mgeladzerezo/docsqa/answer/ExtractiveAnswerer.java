package io.github.mgeladzerezo.docsqa.answer;

import io.github.mgeladzerezo.docsqa.answer.CitationVerifier.PageLocator;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.IntStream;

/**
 * The answer when no language model is configured: the sentences of the retrieved passages that are
 * closest to the question, quoted verbatim, each with its citation.
 *
 * <p>Nothing here is generated. The service does not paraphrase, summarise or decide whether the
 * quotes really answer the question; it shows the best evidence it found and says so, and the API and
 * UI label the answer as extractive.
 */
public class ExtractiveAnswerer {

    /** At most this many passages are quoted. */
    static final int MAX_QUOTES = 3;
    /** A passage is quoted only if its best sentence is at least this close to the best one overall. */
    static final double RELATIVE_CUTOFF = 0.75;

    private final SentenceRanker ranker;
    private final PageLocator pages;

    public ExtractiveAnswerer(SentenceRanker ranker, PageLocator pages) {
        this.ranker = ranker;
        this.pages = pages;
    }

    /**
     * @param lines     one quoted sentence with its marker per entry, e.g. {@code "..." [2]}
     * @param citations the citation behind each line, in the same order
     */
    public record Extract(List<String> lines, List<Citation> citations) {

        public String answer() {
            return String.join("\n\n", lines);
        }
    }

    public Extract answer(String question, List<Source> sources) {
        List<SentenceRanker.Match> matches = ranker.best(sources.stream()
                .map(source -> new SentenceRanker.Request(question, source.text()))
                .toList());
        List<Integer> order = IntStream.range(0, sources.size()).boxed()
                .sorted(Comparator.comparingDouble((Integer i) -> matches.get(i).similarity()).reversed())
                .toList();
        List<String> lines = new ArrayList<>();
        List<Citation> citations = new ArrayList<>();
        double best = order.isEmpty() ? 0 : matches.get(order.getFirst()).similarity();
        for (int index : order) {
            if (lines.size() == MAX_QUOTES || matches.get(index).similarity() < best * RELATIVE_CUTOFF) {
                break;
            }
            Citation citation = CitationVerifier.cite(sources.get(index), matches.get(index), pages);
            citations.add(citation);
            lines.add("\"" + citation.quote() + "\" [" + citation.n() + "]");
        }
        return new Extract(lines, citations);
    }
}
