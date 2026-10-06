package io.github.mgeladzerezo.docsqa.ingest;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.encryption.AccessPermission;
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDTrueTypeFont;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.font.encoding.MacRomanEncoding;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;

/** Small PDFs built with PDFBox for the cases the sample corpus does not contain. */
public final class TestPdfs {

    private static final float LEADING = 15;
    private static final float PARAGRAPH_GAP = 24;

    private TestPdfs() {
    }

    /** One list of lines per page, printed exactly as given; an empty string is a paragraph gap. */
    public static byte[] ofLines(List<List<String>> pages) {
        return build(pdf -> {
            PDFont font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            for (List<String> lines : pages) {
                printLines(pdf, font, lines);
            }
        });
    }

    /**
     * Text set in an embedded TrueType font with Mac Roman encoding, which has code points for the fi
     * and fl ligature glyphs, so U+FB01 and U+FB02 in the input become real ligature glyphs in the PDF.
     */
    public static byte[] withUnicodeFont(List<String> lines) {
        return build(pdf -> {
            try (InputStream ttf = PDDocument.class.getResourceAsStream(
                    "/org/apache/pdfbox/resources/ttf/LiberationSans-Regular.ttf")) {
                printLines(pdf, PDTrueTypeFont.load(pdf, ttf, MacRomanEncoding.INSTANCE), lines);
            }
        });
    }

    /** Pages that contain nothing but a bitmap, like the output of a scanner. */
    public static byte[] scanned(int pages) {
        return build(pdf -> {
            BufferedImage image = new BufferedImage(300, 400, BufferedImage.TYPE_INT_RGB);
            Graphics2D graphics = image.createGraphics();
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, 300, 400);
            graphics.setColor(Color.DARK_GRAY);
            for (int y = 40; y < 380; y += 22) {
                graphics.fillRect(30, y, 240, 6);
            }
            graphics.dispose();
            PDImageXObject scan = LosslessFactory.createFromImage(pdf, image);
            for (int i = 0; i < pages; i++) {
                PDPage page = new PDPage(PDRectangle.A4);
                pdf.addPage(page);
                try (PDPageContentStream content = new PDPageContentStream(pdf, page)) {
                    content.drawImage(scan, 50, 100, 480, 640);
                }
            }
        });
    }

    public static byte[] passwordProtected(String userPassword) {
        return build(pdf -> {
            printLines(pdf, new PDType1Font(Standard14Fonts.FontName.HELVETICA), List.of("Secret text nobody may read."));
            StandardProtectionPolicy policy = new StandardProtectionPolicy("owner-secret", userPassword, new AccessPermission());
            policy.setEncryptionKeyLength(128);
            pdf.protect(policy);
        });
    }

    public static Path write(Path directory, String name, byte[] pdf) {
        try {
            return Files.write(directory.resolve(name), pdf);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void printLines(PDDocument pdf, PDFont font, List<String> lines) throws IOException {
        PDPage page = new PDPage(PDRectangle.A4);
        pdf.addPage(page);
        try (PDPageContentStream content = new PDPageContentStream(pdf, page)) {
            float y = 760;
            for (String line : lines) {
                if (line.isEmpty()) {
                    y -= PARAGRAPH_GAP - LEADING;
                    continue;
                }
                content.beginText();
                content.setFont(font, 11);
                content.newLineAtOffset(72, y);
                content.showText(line);
                content.endText();
                y -= LEADING;
            }
        }
    }

    @FunctionalInterface
    private interface PdfBody {
        void write(PDDocument pdf) throws IOException;
    }

    private static byte[] build(PdfBody body) {
        try (PDDocument pdf = new PDDocument()) {
            body.write(pdf);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            pdf.save(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
