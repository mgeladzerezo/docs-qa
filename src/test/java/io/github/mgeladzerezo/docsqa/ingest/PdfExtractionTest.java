package io.github.mgeladzerezo.docsqa.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.mgeladzerezo.docsqa.ingest.ExtractedDocument.BlockType;
import io.github.mgeladzerezo.docsqa.sample.CorpusDocument;
import io.github.mgeladzerezo.docsqa.sample.CorpusDocument.Element;
import io.github.mgeladzerezo.docsqa.sample.CorpusDocument.Kind;
import io.github.mgeladzerezo.docsqa.sample.CorpusPdfRenderer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Extraction and clean-up end to end on real PDF files generated with PDFBox: the text of every page
 * must come back on exactly that page, without running headers, with paragraphs and headings intact.
 */
class PdfExtractionTest {

    @TempDir
    Path directory;

    private final PdfTextExtractor extractor = new PdfTextExtractor(50, 20);
    private final TextAssembler assembler = new TextAssembler();

    static List<CorpusDocument> corpus() {
        return CorpusDocument.loadBundled();
    }

    @ParameterizedTest
    @MethodSource("corpus")
    void everyPageOfTheSampleCorpusComesBackWithExactlyItsOwnText(CorpusDocument source) {
        ExtractedDocument document = extract(CorpusPdfRenderer.render(source), source.fileName());

        assertThat(document.pages()).hasSameSizeAs(source.pages());
        for (int p = 0; p < source.pages().size(); p++) {
            String expected = source.pages().get(p).stream().map(Element::text).collect(Collectors.joining("\n\n"));
            assertThat(document.pages().get(p).pageNumber()).isEqualTo(p + 1);
            assertThat(document.pageText(document.pages().get(p))).as("page %d of %s", p + 1, source.fileName())
                    .isEqualTo(expected);
        }
        assertThat(document.text()).doesNotContain(source.runningHeader()).doesNotContainPattern("Page \\d+ of \\d+");
    }

    @ParameterizedTest
    @MethodSource("corpus")
    void headingsOfTheSampleCorpusAreRecognised(CorpusDocument source) {
        ExtractedDocument document = extract(CorpusPdfRenderer.render(source), source.fileName());

        List<String> expected = source.pages().stream()
                .flatMap(List::stream)
                .filter(element -> element.kind() != Kind.PARAGRAPH)
                .map(Element::text)
                .toList();
        List<String> actual = document.blocks().stream()
                .filter(block -> block.type() == BlockType.HEADING)
                .map(document::blockText)
                .toList();
        assertThat(actual).isEqualTo(expected);
    }

    @Test
    void wrappedAndHyphenatedLinesAreRejoinedAndParagraphsSeparated() {
        byte[] pdf = TestPdfs.ofLines(List.of(List.of(
                "The cluster keeps three copies of every trans-",
                "action and the proxy routes clients to the pri-",
                "mary node.",
                "",
                "A second paragraph starts here and mentions",
                "a state-of-the-art design.")));

        ExtractedDocument document = extract(pdf, "hyphens.pdf");

        assertThat(document.text()).isEqualTo(
                "The cluster keeps three copies of every transaction and the proxy routes clients to the primary node."
                        + "\n\nA second paragraph starts here and mentions a state-of-the-art design.");
    }

    @Test
    void ligatureGlyphsAreExpandedToPlainLetters() {
        // The PDF really contains the fi and fl ligature glyphs (one glyph each, not two letters).
        byte[] pdf = TestPdfs.withUnicodeFont(List.of("The ﬁnal ﬂoor plan is ﬁled."));

        ExtractedDocument document = extract(pdf, "ligatures.pdf");

        assertThat(document.text()).isEqualTo("The final floor plan is filed.");
    }

    @Test
    void aSentenceRunningOverAPageBreakIsJoinedAndBothPagesKeepTheirPart() {
        byte[] pdf = TestPdfs.ofLines(List.of(
                List.of("Restores are tested every month and the last test", "restored the database in"),
                List.of("forty-seven minutes, which is within the target.", "", "Next paragraph.")));

        ExtractedDocument document = extract(pdf, "pagebreak.pdf");

        assertThat(document.text()).startsWith(
                "Restores are tested every month and the last test restored the database in forty-seven minutes, which");
        int offset = document.text().indexOf("forty-seven");
        assertThat(document.pageAt(offset - 2)).isEqualTo(1);
        assertThat(document.pageAt(offset)).isEqualTo(2);
    }

    @Test
    void aScannedPdfIsRejectedWithAMessageThatSaysWhyAndThatOcrIsOutOfScope() {
        Path file = TestPdfs.write(directory, "scan.pdf", TestPdfs.scanned(3));

        assertThatThrownBy(() -> extractor.extract(file))
                .isInstanceOf(IngestionException.class)
                .hasMessageContaining("No text layer")
                .hasMessageContaining("3 page(s)")
                .hasMessageContaining("scanned")
                .hasMessageContaining("OCR is out of scope");
    }

    @Test
    void aDocumentOverThePageLimitIsRejected() {
        List<List<String>> pages = java.util.stream.IntStream.range(0, 6)
                .mapToObj(i -> List.of("Page " + i + " has enough text to count as a text page."))
                .toList();
        Path file = TestPdfs.write(directory, "long.pdf", TestPdfs.ofLines(pages));

        assertThatThrownBy(() -> new PdfTextExtractor(5, 20).extract(file))
                .isInstanceOf(IngestionException.class)
                .hasMessage("The PDF has 6 pages; the limit is 5.");
    }

    @Test
    void aPasswordProtectedPdfIsRejected() {
        Path file = TestPdfs.write(directory, "locked.pdf", TestPdfs.passwordProtected("user-secret"));

        assertThatThrownBy(() -> extractor.extract(file))
                .isInstanceOf(IngestionException.class)
                .hasMessageContaining("password-protected");
    }

    @Test
    void aFileThatIsNotAPdfIsRejected() throws Exception {
        Path file = Files.write(directory.resolve("fake.pdf"), "%PDF-1.7 this is not really a pdf".getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> extractor.extract(file))
                .isInstanceOf(IngestionException.class)
                .hasMessageContaining("could not be parsed");
    }

    @Test
    void renderingTheSampleCorpusIsReproducible() {
        CorpusDocument source = CorpusDocument.loadBundled().getFirst();

        assertThat(CorpusPdfRenderer.render(source)).isEqualTo(CorpusPdfRenderer.render(source));
    }

    private ExtractedDocument extract(byte[] pdf, String name) {
        return assembler.assemble(extractor.extract(TestPdfs.write(directory, name, pdf)));
    }
}
