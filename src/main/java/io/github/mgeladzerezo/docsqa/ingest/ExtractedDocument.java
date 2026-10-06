package io.github.mgeladzerezo.docsqa.ingest;

import java.util.List;

/**
 * The cleaned text of a PDF as one string, plus the two indexes everything downstream relies on:
 * where each page sits in that string and which ranges are headings or paragraphs.
 *
 * <p>Every later coordinate (chunk offsets, citation quotes, UI highlights) is an offset into
 * {@link #text()}, so "which page is this on" is always answered by {@link #pageAt(int)} and never
 * by re-parsing the PDF.
 *
 * @param text   pages joined by a blank line, or by a single space where a paragraph runs across a page break
 * @param pages  one span per PDF page in order; a page without text has an empty span
 * @param blocks headings and paragraphs in reading order; they do not overlap and cover all non-blank text
 */
public record ExtractedDocument(String text, List<PageSpan> pages, List<Block> blocks) {

    public ExtractedDocument {
        pages = List.copyOf(pages);
        blocks = List.copyOf(blocks);
    }

    /** A page's half-open character range {@code [start, end)} in the document text. */
    public record PageSpan(int pageNumber, int start, int end) {

        public boolean contains(int offset) {
            return offset >= start && offset < end;
        }
    }

    public enum BlockType { HEADING, PARAGRAPH }

    /** A heading or paragraph as a half-open character range in the document text. */
    public record Block(BlockType type, int start, int end) {
    }

    /**
     * The 1-based number of the page whose text contains {@code offset}. Offsets that fall into the
     * separator between two pages belong to no page; they resolve to the following page because a
     * separator is only ever looked up as the start of something.
     */
    public int pageAt(int offset) {
        if (offset < 0 || offset > text.length()) {
            throw new IndexOutOfBoundsException("offset " + offset + " outside document text of length " + text.length());
        }
        int lastNonEmpty = pages.isEmpty() ? 1 : pages.getFirst().pageNumber();
        for (PageSpan page : pages) {
            if (page.contains(offset)) {
                return page.pageNumber();
            }
            if (page.end() > page.start()) {
                if (offset < page.start()) {
                    return page.pageNumber();
                }
                lastNonEmpty = page.pageNumber();
            }
        }
        return lastNonEmpty;
    }

    public String pageText(PageSpan page) {
        return text.substring(page.start(), page.end());
    }

    public String blockText(Block block) {
        return text.substring(block.start(), block.end());
    }
}
