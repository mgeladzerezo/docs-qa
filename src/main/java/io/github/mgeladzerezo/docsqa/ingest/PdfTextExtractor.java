package io.github.mgeladzerezo.docsqa.ingest;

import java.io.IOException;
import java.io.Writer;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;

/**
 * Reads a PDF with PDFBox one page at a time and returns its lines with font size, weight and
 * vertical position. It does no clean-up; {@link TextAssembler} turns the result into document text.
 *
 * <p>Pages are stripped individually ({@code setStartPage(n)}/{@code setEndPage(n)}) so that the page
 * number of every line is exact by construction instead of being inferred from form feeds.
 */
public class PdfTextExtractor {

    private final int maxPages;
    private final int minCharsPerPage;

    /**
     * @param maxPages        documents with more pages are rejected
     * @param minCharsPerPage average number of non-blank characters per page below which the PDF is
     *                        treated as scanned
     */
    public PdfTextExtractor(int maxPages, int minCharsPerPage) {
        this.maxPages = maxPages;
        this.minCharsPerPage = minCharsPerPage;
    }

    /**
     * @throws IngestionException if the file is not a readable PDF, is password-protected, exceeds the
     *                            page limit or has no text layer
     */
    public List<RawPage> extract(Path pdf) {
        try (PDDocument document = Loader.loadPDF(pdf.toFile())) {
            int pageCount = document.getNumberOfPages();
            if (pageCount == 0) {
                throw new IngestionException("The PDF has no pages.");
            }
            if (pageCount > maxPages) {
                throw new IngestionException(
                        "The PDF has %d pages; the limit is %d.".formatted(pageCount, maxPages));
            }
            LineCollector collector = new LineCollector();
            List<RawPage> pages = new ArrayList<>(pageCount);
            long textChars = 0;
            for (int page = 1; page <= pageCount; page++) {
                List<RawPage.Line> lines = collector.linesOf(document, page);
                for (RawPage.Line line : lines) {
                    textChars += line.text().chars().filter(c -> !Character.isWhitespace(c)).count();
                }
                pages.add(new RawPage(page, lines));
            }
            if (textChars < (long) minCharsPerPage * pageCount) {
                throw new IngestionException(
                        ("No text layer found: %d characters on %d page(s). This looks like a scanned PDF. "
                                + "OCR is out of scope for this service; run the file through an OCR tool first.")
                                .formatted(textChars, pageCount));
            }
            return pages;
        } catch (InvalidPasswordException e) {
            throw new IngestionException("The PDF is password-protected and cannot be read.", e);
        } catch (IOException e) {
            throw new IngestionException("The file could not be parsed as a PDF: " + e.getMessage(), e);
        }
    }

    /**
     * A text stripper that records lines instead of writing them. PDFBox calls
     * {@link #writeString(String, List)} once per word group and {@link #writeLineSeparator()} at the
     * end of each visual line, which is exactly the granularity needed here.
     */
    private static final class LineCollector extends PDFTextStripper {

        private final List<RawPage.Line> lines = new ArrayList<>();
        private final StringBuilder current = new StringBuilder();
        private float maxFontSize;
        private float baseline;
        private boolean allBold;
        private boolean anyGlyph;

        LineCollector() {
            setSortByPosition(true);
            setSuppressDuplicateOverlappingText(true);
        }

        List<RawPage.Line> linesOf(PDDocument document, int page) throws IOException {
            lines.clear();
            resetLine();
            setStartPage(page);
            setEndPage(page);
            writeText(document, Writer.nullWriter());
            flushLine();
            return List.copyOf(lines);
        }

        @Override
        protected void writeString(String text, List<TextPosition> positions) {
            current.append(text);
            for (TextPosition position : positions) {
                if (position.getUnicode() == null || position.getUnicode().isBlank()) {
                    continue;
                }
                maxFontSize = Math.max(maxFontSize, position.getFontSizeInPt());
                baseline = Math.max(baseline, position.getYDirAdj());
                allBold &= isBold(position.getFont());
                anyGlyph = true;
            }
        }

        @Override
        protected void writeWordSeparator() {
            current.append(' ');
        }

        @Override
        protected void writeLineSeparator() {
            flushLine();
        }

        // PDFBox does not emit a line separator at paragraph and article (text bead) boundaries,
        // so those are flushed too; otherwise the last line of one column would run into the next.

        @Override
        protected void writeParagraphStart() throws IOException {
            flushLine();
            super.writeParagraphStart();
        }

        @Override
        protected void writeParagraphEnd() throws IOException {
            flushLine();
            super.writeParagraphEnd();
        }

        @Override
        protected void endArticle() throws IOException {
            flushLine();
            super.endArticle();
        }

        private void flushLine() {
            if (anyGlyph) {
                lines.add(new RawPage.Line(current.toString(), maxFontSize, allBold, baseline));
            }
            resetLine();
        }

        private void resetLine() {
            current.setLength(0);
            maxFontSize = 0;
            baseline = 0;
            allBold = true;
            anyGlyph = false;
        }

        private static boolean isBold(PDFont font) {
            if (font == null || font.getName() == null) {
                return false;
            }
            String name = font.getName().toLowerCase();
            return name.contains("bold") || name.contains("black") || name.contains("heavy");
        }
    }
}
