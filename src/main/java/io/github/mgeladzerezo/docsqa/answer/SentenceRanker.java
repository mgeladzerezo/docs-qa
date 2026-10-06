package io.github.mgeladzerezo.docsqa.answer;

import io.github.mgeladzerezo.docsqa.embedding.EmbeddingGateway;
import io.github.mgeladzerezo.docsqa.embedding.Vectors;
import io.github.mgeladzerezo.docsqa.ingest.SentenceSplitter;
import java.util.ArrayList;
import java.util.List;

/**
 * Finds the sentence of a passage that best matches a piece of text, by cosine similarity of their
 * embeddings. It is used twice: to pick the sentences an extractive answer consists of (text = the
 * question), and to pick the quote behind a citation (text = the sentence that carries the marker).
 *
 * <p>Embedding similarity is used instead of word overlap because questions and generated answers
 * paraphrase: "can I expense wine" has to find "alcohol is not reimbursed". The embedding model is
 * local, so this costs one small batch and no API call.
 */
public class SentenceRanker {

    /** Headings and fragments shorter than this are not offered as quotes when anything longer exists. */
    private static final int MIN_SENTENCE_CHARS = 25;

    private final EmbeddingGateway embeddings;

    public SentenceRanker(EmbeddingGateway embeddings) {
        this.embeddings = embeddings;
    }

    /** What to match and where to look for it. */
    public record Request(String text, String passage) {
    }

    /**
     * The best sentence of a passage as a half-open range of offsets into that passage.
     *
     * @param similarity cosine similarity between the request text and the sentence
     */
    public record Match(int start, int end, double similarity) {
    }

    /** Returns one match per request, in order. All texts are embedded in a single batch. */
    public List<Match> best(List<Request> requests) {
        List<String> texts = new ArrayList<>();
        List<List<SentenceSplitter.Span>> sentencesPerRequest = new ArrayList<>();
        for (Request request : requests) {
            List<SentenceSplitter.Span> sentences = sentences(request.passage());
            sentencesPerRequest.add(sentences);
            texts.add(request.text());
            for (SentenceSplitter.Span sentence : sentences) {
                texts.add(request.passage().substring(sentence.start(), sentence.end()));
            }
        }
        List<float[]> vectors = texts.isEmpty() ? List.of() : embeddings.embedTexts(texts);
        List<Match> matches = new ArrayList<>();
        int next = 0;
        for (int r = 0; r < requests.size(); r++) {
            float[] target = vectors.get(next++);
            List<SentenceSplitter.Span> sentences = sentencesPerRequest.get(r);
            Match best = new Match(0, requests.get(r).passage().length(), 0);
            double bestSimilarity = Double.NEGATIVE_INFINITY;
            for (SentenceSplitter.Span sentence : sentences) {
                double similarity = Vectors.cosine(target, vectors.get(next++));
                if (similarity > bestSimilarity) {
                    bestSimilarity = similarity;
                    best = new Match(sentence.start(), sentence.end(), similarity);
                }
            }
            matches.add(best);
        }
        return matches;
    }

    /**
     * The quotable sentences of a passage. Paragraph breaks always end a sentence (a heading line has
     * no full stop), and each span is trimmed so a quote never starts or ends with whitespace.
     */
    static List<SentenceSplitter.Span> sentences(String passage) {
        List<SentenceSplitter.Span> all = new ArrayList<>();
        int paragraphStart = 0;
        while (paragraphStart < passage.length()) {
            int paragraphEnd = passage.indexOf("\n\n", paragraphStart);
            if (paragraphEnd < 0) {
                paragraphEnd = passage.length();
            }
            for (SentenceSplitter.Span span : SentenceSplitter.split(passage, paragraphStart, paragraphEnd)) {
                int start = span.start();
                int end = span.end();
                while (start < end && Character.isWhitespace(passage.charAt(start))) {
                    start++;
                }
                while (end > start && Character.isWhitespace(passage.charAt(end - 1))) {
                    end--;
                }
                if (end > start) {
                    all.add(new SentenceSplitter.Span(start, end));
                }
            }
            paragraphStart = paragraphEnd + 2;
        }
        List<SentenceSplitter.Span> substantial = all.stream()
                .filter(span -> span.end() - span.start() >= MIN_SENTENCE_CHARS)
                .toList();
        return substantial.isEmpty() ? all : substantial;
    }
}
