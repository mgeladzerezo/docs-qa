package io.github.mgeladzerezo.docsqa.ingest;

import java.util.List;

/**
 * What PDFBox gives us for one page before any clean-up: its lines top to bottom, each with the
 * layout facts needed to tell headings, paragraphs and running headers apart.
 */
public record RawPage(int pageNumber, List<Line> lines) {

    public RawPage {
        lines = List.copyOf(lines);
    }

    /**
     * @param text     the line as extracted, untrimmed characters included
     * @param fontSize largest font size on the line, in points
     * @param bold     true when every glyph on the line comes from a bold font
     * @param y        baseline position measured from the top of the page, in points
     */
    public record Line(String text, float fontSize, boolean bold, float y) {
    }
}
