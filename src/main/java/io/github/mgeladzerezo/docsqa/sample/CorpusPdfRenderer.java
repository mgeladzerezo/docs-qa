package io.github.mgeladzerezo.docsqa.sample;

import io.github.mgeladzerezo.docsqa.sample.CorpusDocument.Element;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.GregorianCalendar;
import java.util.List;
import java.util.TimeZone;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentInformation;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;

/**
 * Renders a {@link CorpusDocument} to a real PDF with PDFBox: word-wrapped body text, larger bold
 * headings, a running header and a "Page n of m" footer on every page. The sample PDFs therefore
 * exercise the same extraction path as an uploaded file, including header and footer removal.
 *
 * <p>The output is byte-for-byte reproducible (fixed dates and document id), so the content hash of a
 * sample is stable and re-running the preload never creates duplicates.
 */
public final class CorpusPdfRenderer {

    private static final PDRectangle PAGE = PDRectangle.A4;
    private static final float MARGIN = 72;
    private static final float TOP = PAGE.getHeight() - 96;
    private static final float BOTTOM = 72;
    private static final float BODY_SIZE = 11;
    private static final float BODY_LEADING = 15;
    private static final float PARAGRAPH_GAP = 9;
    private static final float HEADING_SIZE = 14;
    private static final float TITLE_SIZE = 20;
    private static final float RUNNING_SIZE = 9;

    private CorpusPdfRenderer() {
    }

    public static byte[] render(CorpusDocument source) {
        try (PDDocument pdf = new PDDocument()) {
            PDFont regular = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            PDFont bold = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);
            int pageCount = source.pages().size();
            for (int p = 0; p < pageCount; p++) {
                PDPage page = new PDPage(PAGE);
                pdf.addPage(page);
                try (PDPageContentStream content = new PDPageContentStream(pdf, page)) {
                    line(content, regular, RUNNING_SIZE, MARGIN, PAGE.getHeight() - 48, source.runningHeader());
                    line(content, regular, RUNNING_SIZE, MARGIN, 40, "Page %d of %d".formatted(p + 1, pageCount));
                    float y = TOP;
                    for (Element element : source.pages().get(p)) {
                        y = switch (element.kind()) {
                            case TITLE -> block(content, bold, TITLE_SIZE, TITLE_SIZE * 1.3f, y, element.text()) - 14;
                            case HEADING -> block(content, bold, HEADING_SIZE, HEADING_SIZE * 1.3f, y - 8, element.text()) - 4;
                            case PARAGRAPH -> block(content, regular, BODY_SIZE, BODY_LEADING, y, element.text()) - PARAGRAPH_GAP;
                        };
                        if (y < BOTTOM - PARAGRAPH_GAP) {
                            throw new IllegalStateException(
                                    "Page %d of %s does not fit on one PDF page".formatted(p + 1, source.fileName()));
                        }
                    }
                }
            }
            makeReproducible(pdf, source);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            pdf.save(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Draws wrapped text starting at baseline {@code y}; returns the baseline for the following line. */
    private static float block(PDPageContentStream content, PDFont font, float size, float leading, float y, String text)
            throws IOException {
        for (String wrapped : wrap(font, size, text)) {
            line(content, font, size, MARGIN, y, wrapped);
            y -= leading;
        }
        return y;
    }

    private static void line(PDPageContentStream content, PDFont font, float size, float x, float y, String text)
            throws IOException {
        content.beginText();
        content.setFont(font, size);
        content.newLineAtOffset(x, y);
        content.showText(text);
        content.endText();
    }

    private static List<String> wrap(PDFont font, float size, String text) throws IOException {
        float maxWidth = PAGE.getWidth() - 2 * MARGIN;
        List<String> lines = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String word : text.split(" ")) {
            String candidate = current.isEmpty() ? word : current + " " + word;
            if (!current.isEmpty() && font.getStringWidth(candidate) / 1000 * size > maxWidth) {
                lines.add(current.toString());
                current = new StringBuilder(word);
            } else {
                current = new StringBuilder(candidate);
            }
        }
        if (!current.isEmpty()) {
            lines.add(current.toString());
        }
        return lines;
    }

    private static void makeReproducible(PDDocument pdf, CorpusDocument source) {
        Calendar fixed = new GregorianCalendar(TimeZone.getTimeZone("UTC"));
        fixed.clear();
        fixed.set(2025, Calendar.JANUARY, 1);
        PDDocumentInformation info = new PDDocumentInformation();
        info.setTitle(source.fileName());
        info.setProducer("docs-qa sample corpus");
        info.setCreationDate(fixed);
        info.setModificationDate(fixed);
        pdf.setDocumentInformation(info);
        pdf.setDocumentId((long) source.fileName().hashCode());
    }
}
