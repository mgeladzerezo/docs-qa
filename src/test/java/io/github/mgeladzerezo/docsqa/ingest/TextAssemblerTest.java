package io.github.mgeladzerezo.docsqa.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.mgeladzerezo.docsqa.ingest.ExtractedDocument.Block;
import io.github.mgeladzerezo.docsqa.ingest.ExtractedDocument.BlockType;
import io.github.mgeladzerezo.docsqa.ingest.ExtractedDocument.PageSpan;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Clean-up rules on hand-built line lists, where every input is visible in the test. */
class TextAssemblerTest {

    private final TextAssembler assembler = new TextAssembler();

    @Test
    void expandsLigaturesAndDropsInvisibleCharacters() {
        assertThat(TextAssembler.normalizeCharacters("The ﬁnal oﬃce ﬂoor"))
                .isEqualTo("The final office floor");
        assertThat(TextAssembler.normalizeCharacters("co­operate now​  \t twice "))
                .isEqualTo("cooperate now twice");
    }

    @Test
    void joinsLinesOfAParagraphAndUndoesHyphenation() {
        ExtractedDocument document = assembler.assemble(List.of(page(1,
                body("The replication lag is moni-", 100),
                body("tored by the on-call engineer and", 115),
                body("a well-", 130),
                body("Known issue stays hyphenated.", 145))));

        assertThat(document.text())
                .isEqualTo("The replication lag is monitored by the on-call engineer and a well- Known issue stays hyphenated.");
    }

    @Test
    void startsANewParagraphWhereTheLineGapIsLargerThanUsual() {
        ExtractedDocument document = assembler.assemble(List.of(page(1,
                body("First paragraph line one", 100),
                body("first paragraph line two.", 115),
                body("Second paragraph line one", 139),
                body("second paragraph line two.", 154))));

        assertThat(document.text())
                .isEqualTo("First paragraph line one first paragraph line two.\n\nSecond paragraph line one second paragraph line two.");
        assertThat(document.blocks()).extracting(Block::type).containsExactly(BlockType.PARAGRAPH, BlockType.PARAGRAPH);
    }

    @Test
    void detectsHeadingsByFontSizeAndByBoldShortLines() {
        ExtractedDocument document = assembler.assemble(List.of(page(1,
                new RawPage.Line("Big Title", 20, true, 80),
                new RawPage.Line("1. Scope", 14, true, 110),
                body("Body text that is long enough to set the body font size for the document.", 130),
                new RawPage.Line("Bold run-in label", 11, true, 160),
                body("More body text follows the label and ends here.", 175),
                new RawPage.Line("A bold sentence that ends with a full stop.", 11, true, 200))));

        List<String> headings = document.blocks().stream()
                .filter(block -> block.type() == BlockType.HEADING)
                .map(document::blockText)
                .toList();
        assertThat(headings).containsExactly("Big Title", "1. Scope", "Bold run-in label");
    }

    @Test
    void removesLinesRepeatedAtTheTopAndBottomOfMostPages() {
        List<RawPage> pages = new ArrayList<>();
        for (int p = 1; p <= 5; p++) {
            pages.add(page(p,
                    body("ACME Handbook - Confidential", 40),
                    body("Unique sentence of page " + p + " talks about topic " + (char) ('A' + p) + ".", 100),
                    body("The same sentence appears in the middle of every page.", 130),
                    body("Closing remark " + (char) ('P' + p) + " ends page " + p + ".", 160),
                    body("Page " + p + " of 5", 800)));
        }

        ExtractedDocument document = assembler.assemble(pages);

        assertThat(document.text()).doesNotContain("ACME Handbook").doesNotContain("of 5");
        assertThat(document.text()).contains("Unique sentence of page 3", "Closing remark U ends page 5.");
        // A repeated line in the body of the page is content, not a running header.
        assertThat(document.text().split("The same sentence appears", -1)).hasSize(6);
    }

    @Test
    void keepsRepeatedEdgeLinesWhenTheDocumentIsTooShortToTell() {
        ExtractedDocument document = assembler.assemble(List.of(
                page(1, body("Quarterly report", 40), body("Revenue grew.", 100)),
                page(2, body("Quarterly report", 40), body("Costs fell.", 100))));

        assertThat(document.text().split("Quarterly report", -1)).hasSize(3);
    }

    @Test
    void pageSpansCoverExactlyTheTextOfEachPage() {
        ExtractedDocument document = assembler.assemble(List.of(
                page(1, body("Page one has a sentence.", 100)),
                page(2),
                page(3, body("Page three has another.", 100))));

        assertThat(document.pages()).extracting(PageSpan::pageNumber).containsExactly(1, 2, 3);
        assertThat(document.pageText(document.pages().get(0))).isEqualTo("Page one has a sentence.");
        assertThat(document.pageText(document.pages().get(1))).isEmpty();
        assertThat(document.pageText(document.pages().get(2))).isEqualTo("Page three has another.");
        assertThat(document.pageAt(0)).isEqualTo(1);
        assertThat(document.pageAt(document.text().indexOf("Page three"))).isEqualTo(3);
        assertThat(document.pageAt(document.text().length() - 1)).isEqualTo(3);
    }

    @Test
    void joinsAParagraphThatContinuesOnTheNextPage() {
        ExtractedDocument document = assembler.assemble(List.of(
                page(1, body("The approval must be obtained", 100)),
                page(2, body("before the trip is booked.", 100), body("A new paragraph.", 140))));

        assertThat(document.text()).isEqualTo("The approval must be obtained before the trip is booked.\n\nA new paragraph.");
        assertThat(document.blocks()).hasSize(2);
        assertThat(document.blockText(document.blocks().getFirst()))
                .isEqualTo("The approval must be obtained before the trip is booked.");
        assertThat(document.pageText(document.pages().get(0))).isEqualTo("The approval must be obtained");
        assertThat(document.pageText(document.pages().get(1))).isEqualTo("before the trip is booked.\n\nA new paragraph.");
    }

    @Test
    void rejoinsAWordHyphenatedAcrossAPageBreakAndKeepsPageSpansConsistent() {
        ExtractedDocument document = assembler.assemble(List.of(
                page(1, body("Backups are kept in the archi-", 100)),
                page(2, body("val bucket for a year.", 100))));

        assertThat(document.text()).isEqualTo("Backups are kept in the archival bucket for a year.");
        PageSpan first = document.pages().get(0);
        PageSpan second = document.pages().get(1);
        assertThat(first.end()).isEqualTo(second.start());
        assertThat(document.pageText(first)).isEqualTo("Backups are kept in the archi");
        assertThat(document.pageText(second)).isEqualTo("val bucket for a year.");
    }

    @Test
    void doesNotJoinAcrossPagesWhenTheFirstPageEndsASentence() {
        ExtractedDocument document = assembler.assemble(List.of(
                page(1, body("This sentence is complete.", 100)),
                page(2, body("lower-case start of an unrelated list item", 100))));

        assertThat(document.blocks()).hasSize(2);
    }

    private static RawPage page(int number, RawPage.Line... lines) {
        return new RawPage(number, List.of(lines));
    }

    private static RawPage.Line body(String text, float y) {
        return new RawPage.Line(text, 11, false, y);
    }
}
