package io.github.mgeladzerezo.docsqa.ingest;

import io.github.mgeladzerezo.docsqa.ingest.ExtractedDocument.Block;
import io.github.mgeladzerezo.docsqa.ingest.ExtractedDocument.BlockType;
import io.github.mgeladzerezo.docsqa.ingest.ExtractedDocument.PageSpan;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Turns raw PDF lines into clean document text with page and block structure.
 *
 * <p>The clean-up steps, in order:
 * <ol>
 *   <li><b>Characters</b>: typographic ligatures (fi, fl, ffi, ...) are expanded, no-break spaces become
 *       spaces, soft hyphens and zero-width characters are dropped. Without this a word set with an fi
 *       ligature neither matches the keyword query "file" nor tokenises like it. PDFBox already expands
 *       ligature glyphs while it builds words; the mapping is repeated here so the guarantee does not
 *       depend on where the lines came from.</li>
 *   <li><b>Running headers and footers</b>: a line that repeats in the top or bottom two lines of most
 *       pages (ignoring digits, so "Page 3 of 9" matches "Page 4 of 9") is removed. Left in, it would be
 *       glued into the middle of sentences that cross a page break and pollute every chunk.</li>
 *   <li><b>Blocks</b>: lines become headings (larger than body text, or short and bold) and paragraphs
 *       (split where the vertical gap is larger than the page's normal line spacing).</li>
 *   <li><b>Hyphenation</b>: a line ending in "letter-" followed by a lower-case start is joined without
 *       the hyphen ("docu-" + "ment").</li>
 *   <li><b>Page joins</b>: a paragraph that visibly continues on the next page is joined with a space,
 *       so sentences are not cut at page breaks.</li>
 * </ol>
 *
 * <p>Known trade-offs: a genuinely hyphenated compound broken at its hyphen ("well-" + "known") loses the
 * hyphen; paragraphs marked only by first-line indentation are not separated; multi-column reading
 * order is whatever PDFBox's position sort yields.
 */
public class TextAssembler {

    /** Lines inspected at the top and at the bottom of each page when looking for running headers. */
    private static final int EDGE_LINES = 2;
    /** Share of pages a line must repeat on to count as a running header or footer. */
    private static final double REPEAT_SHARE = 0.6;
    private static final int MIN_PAGES_FOR_REPEAT_DETECTION = 3;
    /** A line this much larger than body text is a heading. */
    private static final float HEADING_SIZE_RATIO = 1.15f;
    private static final int MAX_HEADING_CHARS = 120;
    /** A vertical gap this much larger than the page's usual line gap starts a new paragraph. */
    private static final float PARAGRAPH_GAP_RATIO = 1.3f;
    /**
     * Upper bound for "normal" line spacing as a multiple of the font size. It only matters on pages
     * without a single multi-line paragraph, where every measured gap is a paragraph gap; 1.6 still
     * lets double-spaced text (2.0) through because the gap test multiplies by the ratio above.
     */
    private static final float MAX_LEADING = 1.6f;
    private static final String BLOCK_SEPARATOR = "\n\n";

    public ExtractedDocument assemble(List<RawPage> rawPages) {
        List<List<CleanLine>> pages = new ArrayList<>(rawPages.size());
        for (RawPage page : rawPages) {
            List<CleanLine> lines = new ArrayList<>();
            for (RawPage.Line line : page.lines()) {
                String text = normalizeCharacters(line.text());
                if (!text.isEmpty()) {
                    lines.add(new CleanLine(text, line.fontSize(), line.bold(), line.y()));
                }
            }
            pages.add(lines);
        }
        removeRunningLines(pages);
        float bodySize = bodyFontSize(pages);

        StringBuilder text = new StringBuilder();
        List<PageSpan> spans = new ArrayList<>();
        List<Block> blocks = new ArrayList<>();
        for (int p = 0; p < pages.size(); p++) {
            List<PageBlock> pageBlocks = toBlocks(pages.get(p), bodySize);
            int pageStart = -1;
            for (int b = 0; b < pageBlocks.size(); b++) {
                PageBlock block = pageBlocks.get(b);
                boolean continuesPrevious = b == 0 && !blocks.isEmpty() && continuesAcrossPage(text, blocks.getLast(), block);
                if (continuesPrevious) {
                    if (endsWithBrokenWord(text, block.text())) {
                        dropTrailingHyphen(text, spans);
                    } else {
                        text.append(' ');
                    }
                    pageStart = text.length();
                    text.append(block.text());
                    Block previous = blocks.removeLast();
                    blocks.add(new Block(BlockType.PARAGRAPH, previous.start(), text.length()));
                } else {
                    if (!text.isEmpty()) {
                        text.append(BLOCK_SEPARATOR);
                    }
                    if (pageStart < 0) {
                        pageStart = text.length();
                    }
                    int start = text.length();
                    text.append(block.text());
                    blocks.add(new Block(block.type(), start, text.length()));
                }
            }
            int number = rawPages.get(p).pageNumber();
            spans.add(pageStart < 0
                    ? new PageSpan(number, text.length(), text.length())
                    : new PageSpan(number, pageStart, text.length()));
        }
        return new ExtractedDocument(text.toString(), spans, blocks);
    }

    /** Expands ligatures and removes invisible characters; collapses runs of whitespace. */
    static String normalizeCharacters(String raw) {
        StringBuilder out = new StringBuilder(raw.length());
        boolean pendingSpace = false;
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            String replacement = switch (c) {
                case 'ﬀ' -> "ff";
                case 'ﬁ' -> "fi";
                case 'ﬂ' -> "fl";
                case 'ﬃ' -> "ffi";
                case 'ﬄ' -> "ffl";
                case 'ﬅ', 'ﬆ' -> "st";
                case '­', '​', '‌', '‍', '﻿' -> "";
                default -> null;
            };
            if (replacement != null) {
                if (!replacement.isEmpty()) {
                    if (pendingSpace && !out.isEmpty()) {
                        out.append(' ');
                    }
                    pendingSpace = false;
                    out.append(replacement);
                }
            } else if (Character.isWhitespace(c) || c == ' ' || Character.isISOControl(c)) {
                pendingSpace = true;
            } else {
                if (pendingSpace && !out.isEmpty()) {
                    out.append(' ');
                }
                pendingSpace = false;
                out.append(c);
            }
        }
        return out.toString();
    }

    private static void removeRunningLines(List<List<CleanLine>> pages) {
        if (pages.size() < MIN_PAGES_FOR_REPEAT_DETECTION) {
            return;
        }
        Map<String, Integer> topCounts = new HashMap<>();
        Map<String, Integer> bottomCounts = new HashMap<>();
        for (List<CleanLine> lines : pages) {
            Set<String> top = new HashSet<>();
            Set<String> bottom = new HashSet<>();
            for (int i = 0; i < Math.min(EDGE_LINES, lines.size()); i++) {
                top.add(repeatKey(lines.get(i).text()));
                bottom.add(repeatKey(lines.get(lines.size() - 1 - i).text()));
            }
            top.forEach(key -> topCounts.merge(key, 1, Integer::sum));
            bottom.forEach(key -> bottomCounts.merge(key, 1, Integer::sum));
        }
        int threshold = Math.max(MIN_PAGES_FOR_REPEAT_DETECTION, (int) Math.ceil(pages.size() * REPEAT_SHARE));
        for (List<CleanLine> lines : pages) {
            // Bottom first so that removing a footer does not shift the indexes checked for the header.
            for (int i = 0; i < EDGE_LINES && !lines.isEmpty(); i++) {
                if (bottomCounts.getOrDefault(repeatKey(lines.getLast().text()), 0) >= threshold) {
                    lines.removeLast();
                } else {
                    break;
                }
            }
            for (int i = 0; i < EDGE_LINES && !lines.isEmpty(); i++) {
                if (topCounts.getOrDefault(repeatKey(lines.getFirst().text()), 0) >= threshold) {
                    lines.removeFirst();
                } else {
                    break;
                }
            }
        }
    }

    /** Two lines are "the same" running line if they differ only in digits, case and spacing. */
    private static String repeatKey(String line) {
        return line.toLowerCase().replaceAll("\\d+", "#").replaceAll("\\s+", " ").strip();
    }

    /** The font size carrying the most characters is the body size headings are compared to. */
    private static float bodyFontSize(List<List<CleanLine>> pages) {
        Map<Float, Integer> charsBySize = new HashMap<>();
        for (List<CleanLine> lines : pages) {
            for (CleanLine line : lines) {
                charsBySize.merge(Math.round(line.fontSize() * 2) / 2f, line.text().length(), Integer::sum);
            }
        }
        return charsBySize.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse(0f);
    }

    private static List<PageBlock> toBlocks(List<CleanLine> lines, float bodySize) {
        List<PageBlock> blocks = new ArrayList<>();
        if (lines.isEmpty()) {
            return blocks;
        }
        float usualGap = usualLineGap(lines);
        StringBuilder current = new StringBuilder();
        BlockType currentType = null;
        CleanLine previous = null;
        for (CleanLine line : lines) {
            BlockType type = isHeading(line, bodySize) ? BlockType.HEADING : BlockType.PARAGRAPH;
            boolean sameBlock = previous != null
                    && type == currentType
                    && line.y() - previous.y() <= Math.min(usualGap, previous.fontSize() * MAX_LEADING) * PARAGRAPH_GAP_RATIO
                    && (type == BlockType.PARAGRAPH || Math.abs(line.fontSize() - previous.fontSize()) < 0.5f);
            if (!sameBlock && currentType != null) {
                blocks.add(new PageBlock(currentType, current.toString()));
                current.setLength(0);
            }
            appendLine(current, line.text());
            currentType = type;
            previous = line;
        }
        blocks.add(new PageBlock(currentType, current.toString()));
        return blocks;
    }

    /**
     * The page's normal baseline distance between consecutive lines: the lower quartile of all gaps.
     * A quantile of the page's own gaps (instead of a multiple of the font size) keeps the paragraph
     * test correct for any leading, and the lower quartile stays at the in-paragraph spacing even on
     * pages where most gaps are paragraph gaps.
     */
    private static float usualLineGap(List<CleanLine> lines) {
        float[] gaps = new float[Math.max(0, lines.size() - 1)];
        int count = 0;
        for (int i = 1; i < lines.size(); i++) {
            float gap = lines.get(i).y() - lines.get(i - 1).y();
            if (gap > 0) {
                gaps[count++] = gap;
            }
        }
        if (count == 0) {
            return lines.getFirst().fontSize() * 1.2f;
        }
        Arrays.sort(gaps, 0, count);
        return gaps[(count - 1) / 4];
    }

    private static boolean isHeading(CleanLine line, float bodySize) {
        String text = line.text();
        if (text.length() > MAX_HEADING_CHARS || text.chars().noneMatch(Character::isLetter)) {
            return false;
        }
        if (bodySize > 0 && line.fontSize() >= bodySize * HEADING_SIZE_RATIO) {
            return true;
        }
        char last = text.charAt(text.length() - 1);
        return line.bold() && text.length() <= 80 && last != '.' && last != ',' && last != ';';
    }

    /** Appends a line to a block, undoing end-of-line hyphenation. */
    private static void appendLine(StringBuilder block, String line) {
        if (block.isEmpty()) {
            block.append(line);
            return;
        }
        if (endsWithBrokenWord(block, line)) {
            block.setLength(block.length() - 1);
        } else {
            block.append(' ');
        }
        block.append(line);
    }

    /** True for "...docu-" followed by "ment ...": two letters, a hyphen, then a lower-case start. */
    private static boolean endsWithBrokenWord(CharSequence text, String nextLine) {
        int end = text.length();
        return end >= 3
                && text.charAt(end - 1) == '-'
                && Character.isLetter(text.charAt(end - 2))
                && Character.isLetter(text.charAt(end - 3))
                && Character.isLowerCase(nextLine.charAt(0));
    }

    /** Removes the hyphen that ends the text and pulls back the page spans that ended on it. */
    private static void dropTrailingHyphen(StringBuilder text, List<PageSpan> spans) {
        int oldLength = text.length();
        text.setLength(oldLength - 1);
        for (int i = spans.size() - 1; i >= 0 && spans.get(i).end() == oldLength; i--) {
            PageSpan span = spans.get(i);
            spans.set(i, new PageSpan(span.pageNumber(), Math.min(span.start(), oldLength - 1), oldLength - 1));
        }
    }

    /**
     * True when the first block of a page is the rest of the paragraph that ended the previous page:
     * that paragraph stops without sentence-final punctuation and the new block starts in lower case.
     */
    private static boolean continuesAcrossPage(StringBuilder text, Block previous, PageBlock next) {
        if (previous.type() != BlockType.PARAGRAPH || next.type() != BlockType.PARAGRAPH) {
            return false;
        }
        char last = text.charAt(text.length() - 1);
        boolean openEnded = Character.isLetterOrDigit(last) || last == ',' || last == ';' || last == '-';
        return openEnded && Character.isLowerCase(next.text().charAt(0));
    }

    private record CleanLine(String text, float fontSize, boolean bold, float y) {
    }

    private record PageBlock(BlockType type, String text) {
    }
}
