package io.github.mgeladzerezo.docsqa.answer;

import io.github.mgeladzerezo.docsqa.ingest.SentenceSplitter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Checks a generated answer against the sources that were actually supplied.
 *
 * <p>The model is asked to cite as {@code [n]}, but nothing stops it from writing {@code [7]} when
 * five sources were given. A citation is only worth something if it can be followed, so:
 * <ul>
 *   <li>every marker is parsed ({@code [1]}, {@code [1, 3]}, {@code [2-4]}) and each number is checked
 *       against the supplied source numbers;</li>
 *   <li>numbers that match nothing are removed from the text and reported, never silently kept;</li>
 *   <li>each valid number is resolved to a document, a page and a verbatim quote: the sentence of that
 *       source closest to the statement carrying the marker. The quote is cut out of the source text,
 *       so it is a real passage of the document by construction.</li>
 * </ul>
 * What this does not prove is that the cited passage <em>supports</em> the statement; that needs an
 * entailment check and is listed under limitations in the README.
 */
public class CitationVerifier {

    /** [1], [ 2 ], [1, 3], [1;3], [2-4]. Up to three digits, so a year such as [2024] is not a marker. */
    private static final Pattern MARKER = Pattern.compile("\\[\\s*(\\d{1,3}(?:\\s*[,;\\-–]\\s*\\d{1,3})*)\\s*]");
    private static final int MAX_RANGE = 20;
    private static final int MAX_CLAIM_CHARS = 600;

    /** Resolves an offset of a document's text to its 1-based page number. */
    @FunctionalInterface
    public interface PageLocator {

        int pageAt(UUID documentId, int offset);
    }

    /**
     * @param answer    the answer with unknown citation numbers removed and markers normalised to {@code [n]}
     * @param citations one per cited source, in order of first appearance
     * @param invalid   cited numbers that match no supplied source, ascending
     * @param notFound  the answer is the agreed "not found" sentence
     * @param uncited   the answer makes statements but has no valid citation
     */
    public record Verified(String answer, List<Citation> citations, List<Integer> invalid, boolean notFound, boolean uncited) {
    }

    private final SentenceRanker ranker;
    private final PageLocator pages;

    public CitationVerifier(SentenceRanker ranker, PageLocator pages) {
        this.ranker = ranker;
        this.pages = pages;
    }

    public Verified verify(String rawAnswer, List<Source> sources) {
        Set<Integer> invalid = new TreeSet<>();
        String answer = normalizeMarkers(rawAnswer.strip(), sources.size(), invalid);
        String withoutMarkers = MARKER.matcher(answer).replaceAll("").strip();
        if (withoutMarkers.isEmpty() || isNotFound(withoutMarkers)) {
            return new Verified(PromptBuilder.NOT_FOUND, List.of(), List.copyOf(invalid), true, false);
        }
        Map<Integer, String> claims = claimsBySource(answer);
        List<Source> cited = claims.keySet().stream().map(n -> sources.get(n - 1)).toList();
        List<SentenceRanker.Match> matches = ranker.best(cited.stream()
                .map(source -> new SentenceRanker.Request(claims.get(source.n()), source.text()))
                .toList());
        List<Citation> citations = new ArrayList<>();
        for (int i = 0; i < cited.size(); i++) {
            citations.add(cite(cited.get(i), matches.get(i), pages));
        }
        return new Verified(answer, citations, List.copyOf(invalid), false, citations.isEmpty());
    }

    /** Builds the citation for a sentence of a source. Shared with the extractive answer. */
    static Citation cite(Source source, SentenceRanker.Match match, PageLocator pages) {
        int quoteStart = source.charStart() + match.start();
        int page = source.pageStart() == source.pageEnd() ? source.pageStart() : pages.pageAt(source.documentId(), quoteStart);
        return new Citation(source.n(), source.documentId(), source.filename(), source.heading(), page,
                source.text().substring(match.start(), match.end()), quoteStart, source.charStart() + match.end());
    }

    private static boolean isNotFound(String text) {
        String expected = PromptBuilder.NOT_FOUND.substring(0, PromptBuilder.NOT_FOUND.length() - 1);
        String actual = text.endsWith(".") ? text.substring(0, text.length() - 1) : text;
        return actual.equalsIgnoreCase(expected);
    }

    /** Rewrites every marker to its valid numbers as {@code [a][b]}, dropping the rest. */
    private static String normalizeMarkers(String answer, int sourceCount, Set<Integer> invalid) {
        Matcher matcher = MARKER.matcher(answer);
        StringBuilder out = new StringBuilder();
        int last = 0;
        while (matcher.find()) {
            Set<Integer> valid = new LinkedHashSet<>();
            for (int number : numbers(matcher.group(1))) {
                if (number >= 1 && number <= sourceCount) {
                    valid.add(number);
                } else {
                    invalid.add(number);
                }
            }
            out.append(answer, last, matcher.start());
            if (valid.isEmpty()) {
                // Remove the blank that preceded the dropped marker: "limit [9]." becomes "limit."
                while (!out.isEmpty() && out.charAt(out.length() - 1) == ' ') {
                    out.setLength(out.length() - 1);
                }
            } else {
                valid.forEach(number -> out.append('[').append(number).append(']'));
            }
            last = matcher.end();
        }
        return out.append(answer, last, answer.length()).toString();
    }

    private static List<Integer> numbers(String body) {
        List<Integer> numbers = new ArrayList<>();
        for (String part : body.split("[,;]")) {
            String[] range = part.strip().split("\\s*[\\-–]\\s*");
            int from = Integer.parseInt(range[0].strip());
            int to = Integer.parseInt(range[range.length - 1].strip());
            if (range.length == 2 && to > from && to - from <= MAX_RANGE) {
                for (int number = from; number <= to; number++) {
                    numbers.add(number);
                }
            } else {
                for (String single : range) {
                    numbers.add(Integer.parseInt(single.strip()));
                }
            }
        }
        return numbers;
    }

    /**
     * For each cited source, the text of the sentences that cite it, in order of first appearance.
     * A marker written after the full stop ("... 140 EUR. [1]") forms a sentence of its own; it
     * belongs to the statement before it.
     */
    private static Map<Integer, String> claimsBySource(String answer) {
        Map<Integer, StringBuilder> claims = new LinkedHashMap<>();
        String previousStatement = "";
        for (SentenceSplitter.Span span : SentenceSplitter.split(answer, 0, answer.length())) {
            String sentence = answer.substring(span.start(), span.end());
            String statement = MARKER.matcher(sentence).replaceAll("").strip();
            if (statement.chars().noneMatch(Character::isLetterOrDigit)) {
                statement = previousStatement;
            }
            Matcher matcher = MARKER.matcher(sentence);
            while (matcher.find()) {
                for (int number : numbers(matcher.group(1))) {
                    StringBuilder claim = claims.computeIfAbsent(number, _ -> new StringBuilder());
                    if (claim.length() + statement.length() <= MAX_CLAIM_CHARS && claim.indexOf(statement) < 0) {
                        claim.append(claim.isEmpty() ? "" : " ").append(statement);
                    }
                }
            }
            previousStatement = statement;
        }
        Map<Integer, String> result = new LinkedHashMap<>();
        claims.forEach((number, claim) -> result.put(number, claim.toString()));
        return result;
    }
}
