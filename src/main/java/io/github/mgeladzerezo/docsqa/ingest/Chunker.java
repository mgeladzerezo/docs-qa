package io.github.mgeladzerezo.docsqa.ingest;

import io.github.mgeladzerezo.docsqa.ingest.ExtractedDocument.Block;
import io.github.mgeladzerezo.docsqa.ingest.ExtractedDocument.BlockType;
import java.util.ArrayList;
import java.util.List;

/**
 * Structure-aware chunking: sections first, then paragraphs, then sentences.
 *
 * <p>The document is cut into <em>units</em>, the smallest pieces a chunk boundary may fall between: a
 * heading, or one sentence of a paragraph. Units are grouped into sections (a heading and everything
 * up to the next heading), and each section is packed greedily:
 * <ol>
 *   <li>fill a chunk with whole units up to {@code targetTokens};</li>
 *   <li>prefer a paragraph boundary: finish the current paragraph if that stays within
 *       {@code maxTokens}, otherwise fall back to the previous paragraph end if the chunk would still
 *       be at least 60% full, otherwise cut between sentences;</li>
 *   <li>a section tail smaller than {@code minTokens} is absorbed instead of becoming a fragment;</li>
 *   <li>the next chunk starts with the last whole sentences of this one, up to {@code overlapTokens}.</li>
 * </ol>
 *
 * <p>Guarantees, each enforced by {@code ChunkerTest}:
 * <ul>
 *   <li>every non-blank character of the document is in at least one chunk;</li>
 *   <li>{@code chunk.content()} equals the document text between {@code charStart} and {@code charEnd};</li>
 *   <li>no chunk exceeds {@code maxTokens};</li>
 *   <li>chunks start and end on sentence boundaries, except inside a single sentence longer than
 *       {@code targetTokens}, which is cut between words because there is no better place;</li>
 *   <li>chunks never span a heading and overlap never crosses one, so a chunk's heading is true for all
 *       of its text;</li>
 *   <li>the overlap is at most {@code overlapTokens} and always leaves room for new text, so the
 *       chunker terminates and chunk starts strictly increase.</li>
 * </ul>
 */
public final class Chunker {

    /** A chunk may stop early at a paragraph end only if it is at least this full. */
    private static final double MIN_FILL = 0.6;

    private final int targetTokens;
    private final int maxTokens;
    private final int overlapTokens;
    private final int minTokens;
    private final TokenEstimator estimator;

    public Chunker(int targetTokens, int maxTokens, int overlapTokens, int minTokens, TokenEstimator estimator) {
        if (targetTokens <= 0 || overlapTokens < 0 || minTokens < 0) {
            throw new IllegalArgumentException("token sizes must be positive");
        }
        if (targetTokens + overlapTokens > maxTokens) {
            // Otherwise an overlap followed by one full-size sentence could not fit into a chunk.
            throw new IllegalArgumentException("targetTokens + overlapTokens must not exceed maxTokens");
        }
        if (overlapTokens >= targetTokens || minTokens >= targetTokens) {
            throw new IllegalArgumentException("overlapTokens and minTokens must be smaller than targetTokens");
        }
        this.targetTokens = targetTokens;
        this.maxTokens = maxTokens;
        this.overlapTokens = overlapTokens;
        this.minTokens = minTokens;
        this.estimator = estimator;
    }

    public List<Chunk> chunk(ExtractedDocument document) {
        List<Chunk> chunks = new ArrayList<>();
        for (Section section : sections(document)) {
            pack(document, section, chunks);
        }
        return chunks;
    }

    /**
     * @param end          exclusive end without trailing whitespace
     * @param heading      the unit is a heading line
     * @param paragraphEnd the unit is the last one of its paragraph (always true for headings)
     */
    private record Unit(int start, int end, int tokens, boolean heading, boolean paragraphEnd) {
    }

    private record Section(String heading, List<Unit> units) {
    }

    private List<Section> sections(ExtractedDocument document) {
        String text = document.text();
        List<Section> sections = new ArrayList<>();
        List<Unit> current = new ArrayList<>();
        String heading = null;
        boolean hasBody = false;
        for (Block block : document.blocks()) {
            if (block.type() == BlockType.HEADING) {
                // A heading directly followed by another heading (a title above the first section)
                // has no body of its own; it stays with the section that follows.
                if (hasBody) {
                    sections.add(new Section(heading, current));
                    current = new ArrayList<>();
                    hasBody = false;
                }
                heading = document.blockText(block);
                addUnits(text, block.start(), block.end(), true, current);
            } else {
                for (SentenceSplitter.Span sentence : SentenceSplitter.split(text, block.start(), block.end())) {
                    addUnits(text, sentence.start(), sentence.end(), false, current);
                }
                markParagraphEnd(current);
                hasBody = true;
            }
        }
        if (!current.isEmpty()) {
            sections.add(new Section(heading, current));
        }
        return sections;
    }

    /** Adds the range as one unit, or as several word-aligned pieces if it is larger than a chunk target. */
    private void addUnits(String text, int start, int end, boolean heading, List<Unit> units) {
        while (end > start && Character.isWhitespace(text.charAt(end - 1))) {
            end--;
        }
        if (end <= start) {
            return;
        }
        int tokens = estimator.count(text.subSequence(start, end));
        if (tokens <= targetTokens) {
            units.add(new Unit(start, end, tokens, heading, heading));
            return;
        }
        // A single sentence larger than a whole chunk: cut it between words, or inside a word if one
        // "word" alone is too large (a long hash or encoded blob). Quadratic, but only for such sentences.
        int pieceStart = start;
        int lastBreak = -1;
        for (int i = start; i < end; i++) {
            if (Character.isWhitespace(text.charAt(i))) {
                lastBreak = i;
            } else if (estimator.count(text.subSequence(pieceStart, i + 1)) > targetTokens) {
                int cut = lastBreak > pieceStart ? lastBreak : i;
                units.add(piece(text, pieceStart, cut, heading));
                pieceStart = skipWhitespace(text, cut, end);
            }
        }
        Unit last = piece(text, pieceStart, end, heading);
        units.add(new Unit(last.start(), last.end(), last.tokens(), heading, heading));
    }

    private Unit piece(String text, int start, int end, boolean heading) {
        while (end > start && Character.isWhitespace(text.charAt(end - 1))) {
            end--;
        }
        return new Unit(start, end, estimator.count(text.subSequence(start, end)), heading, false);
    }

    private static int skipWhitespace(String text, int index, int end) {
        while (index < end && Character.isWhitespace(text.charAt(index))) {
            index++;
        }
        return index;
    }

    private static void markParagraphEnd(List<Unit> units) {
        if (!units.isEmpty()) {
            Unit last = units.removeLast();
            units.add(new Unit(last.start(), last.end(), last.tokens(), last.heading(), true));
        }
    }

    private void pack(ExtractedDocument document, Section section, List<Chunk> out) {
        List<Unit> units = section.units();
        int n = units.size();
        int start = 0;  // first unit of the chunk being built, overlap included
        int fresh = 0;  // first unit no earlier chunk contains
        while (fresh < n) {
            int tokens = sum(units, start, fresh);
            int end = fresh;
            while (end < n && (end == fresh || tokens + units.get(end).tokens() <= targetTokens)) {
                tokens += units.get(end++).tokens();
            }
            // Do not strand headings at the end of a chunk, separated from the text they introduce.
            while (end < n && units.get(end - 1).heading() && tokens + units.get(end).tokens() <= maxTokens) {
                tokens += units.get(end++).tokens();
            }
            if (end < n && !units.get(end - 1).paragraphEnd()) {
                int paragraphEnd = end;
                while (!units.get(paragraphEnd - 1).paragraphEnd()) {
                    paragraphEnd++;
                }
                int extended = tokens + sum(units, end, paragraphEnd);
                if (extended <= maxTokens) {
                    end = paragraphEnd;
                    tokens = extended;
                } else {
                    int previousEnd = end - 1;
                    while (previousEnd > fresh && !units.get(previousEnd - 1).paragraphEnd()) {
                        previousEnd--;
                    }
                    int shrunk = tokens - sum(units, previousEnd, end);
                    if (previousEnd > fresh && !units.get(previousEnd - 1).heading() && shrunk >= targetTokens * MIN_FILL) {
                        end = previousEnd;
                        tokens = shrunk;
                    }
                }
            }
            int tail = sum(units, end, n);
            if (tail > 0 && tail < minTokens && tokens + tail <= maxTokens) {
                end = n;
                tokens += tail;
            }
            out.add(toChunk(document, section, units.get(start), units.get(end - 1), tokens, out.size()));

            int nextStart = end;
            int overlap = 0;
            while (end < n
                    && nextStart - 1 > start
                    && !units.get(nextStart - 1).heading()
                    && overlap + units.get(nextStart - 1).tokens() <= overlapTokens) {
                overlap += units.get(--nextStart).tokens();
            }
            fresh = end;
            start = nextStart;
        }
    }

    private static int sum(List<Unit> units, int from, int to) {
        int total = 0;
        for (int i = from; i < to; i++) {
            total += units.get(i).tokens();
        }
        return total;
    }

    private static Chunk toChunk(ExtractedDocument document, Section section, Unit first, Unit last, int tokens, int ordinal) {
        return new Chunk(
                ordinal,
                document.text().substring(first.start(), last.end()),
                section.heading(),
                document.pageAt(first.start()),
                document.pageAt(last.end() - 1),
                first.start(),
                last.end(),
                tokens);
    }
}
