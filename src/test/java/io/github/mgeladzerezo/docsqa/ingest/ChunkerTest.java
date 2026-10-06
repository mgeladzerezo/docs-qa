package io.github.mgeladzerezo.docsqa.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.mgeladzerezo.docsqa.ingest.ExtractedDocument.Block;
import io.github.mgeladzerezo.docsqa.ingest.ExtractedDocument.BlockType;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Proves the guarantees listed on {@link Chunker}: coverage, exact offsets, size ceiling, sentence
 * and heading boundaries, bounded overlap and exact page mapping, on hand-built cases and on a few
 * hundred randomly generated documents.
 */
class ChunkerTest {

    private static final TokenEstimator TOKENS = TokenEstimator.HEURISTIC;
    private static final int TARGET = 60;
    private static final int MAX = 80;
    private static final int OVERLAP = 15;
    private static final int MIN = 12;

    private final Chunker chunker = new Chunker(TARGET, MAX, OVERLAP, MIN, TOKENS);

    @Test
    void randomDocumentsSatisfyEveryInvariant() {
        for (int seed = 0; seed < 300; seed++) {
            ExtractedDocument document = randomDocument(new Random(seed), false);
            List<Chunk> chunks = chunker.chunk(document);
            assertInvariants(document, chunks);
        }
    }

    @Test
    void documentsWithOversizedSentencesStillCoverTheTextAndRespectTheCeiling() {
        for (int seed = 0; seed < 100; seed++) {
            ExtractedDocument document = randomDocument(new Random(1000 + seed), true);
            List<Chunk> chunks = chunker.chunk(document);
            assertInvariants(document, chunks);
        }
    }

    @Test
    void invariantsHoldForOtherParameterSets() {
        int[][] configs = {{30, 45, 10, 5}, {120, 150, 30, 20}, {200, 256, 40, 40}, {40, 40, 0, 0}};
        for (int[] c : configs) {
            Chunker other = new Chunker(c[0], c[1], c[2], c[3], TOKENS);
            for (int seed = 0; seed < 60; seed++) {
                ExtractedDocument document = randomDocument(new Random(5000 + seed), false);
                assertInvariants(document, other.chunk(document), c[0], c[1], c[2]);
            }
        }
    }

    @Test
    void pageRangeOfEveryChunkIsExactlyThePagesItsTextComesFrom() {
        DocBuilder builder = new DocBuilder();
        int sentence = 0;
        for (int page = 1; page <= 7; page++) {
            if (page == 4) {
                builder.newPage(); // page 4 is blank: page numbers must not shift
                continue;
            }
            for (int paragraph = 0; paragraph < 3; paragraph++) {
                StringBuilder text = new StringBuilder();
                for (int s = 0; s < 5; s++) {
                    text.append("Marker p%dq sentence number %d talks about nothing in particular. ".formatted(page, sentence++));
                }
                builder.paragraph(text.toString().strip());
            }
            builder.newPage();
        }
        ExtractedDocument document = builder.build();
        List<Chunk> chunks = chunker.chunk(document);

        assertThat(chunks).hasSizeGreaterThan(8);
        Pattern marker = Pattern.compile("Marker p(\\d+)q");
        Set<Integer> pagesSeen = new HashSet<>();
        for (Chunk chunk : chunks) {
            Set<Integer> pagesInText = new TreeSet<>();
            Matcher matcher = marker.matcher(chunk.content());
            while (matcher.find()) {
                pagesInText.add(Integer.parseInt(matcher.group(1)));
            }
            Set<Integer> declared = new TreeSet<>();
            for (int page = chunk.pageStart(); page <= chunk.pageEnd(); page++) {
                if (page != 4) {
                    declared.add(page);
                }
            }
            assertThat(declared).as("pages of chunk %d", chunk.ordinal()).isEqualTo(pagesInText);
            pagesSeen.addAll(pagesInText);
        }
        assertThat(pagesSeen).containsExactlyInAnyOrder(1, 2, 3, 5, 6, 7);
        assertThat(chunks).anySatisfy(chunk -> assertThat(chunk.pageEnd()).isGreaterThan(chunk.pageStart()));
    }

    @Test
    void overlapRepeatsTheLastWholeSentencesOfThePreviousChunk() {
        ExtractedDocument document = new DocBuilder()
                .paragraph(sentences("alpha", 14))
                .build();
        List<Chunk> chunks = chunker.chunk(document);

        assertThat(chunks).hasSizeGreaterThan(1);
        for (int i = 1; i < chunks.size(); i++) {
            Chunk previous = chunks.get(i - 1);
            Chunk next = chunks.get(i);
            assertThat(next.charStart()).isLessThan(previous.charEnd());
            String shared = document.text().substring(next.charStart(), previous.charEnd());
            assertThat(shared).startsWith("The alpha").endsWith(".");
            assertThat(previous.content()).endsWith(shared);
            assertThat(next.content()).startsWith(shared);
            assertThat(TOKENS.count(shared)).isBetween(1, OVERLAP);
        }
    }

    @Test
    void chunksNeverSpanAHeadingAndCarryTheHeadingOfTheirSection() {
        ExtractedDocument document = new DocBuilder()
                .heading("Document title")
                .heading("First section")
                .paragraph(sentences("first", 12))
                .heading("Second section")
                .paragraph(sentences("second", 3))
                .build();
        List<Chunk> chunks = chunker.chunk(document);

        assertThat(chunks.getFirst().content()).startsWith("Document title\n\nFirst section\n\nThe first");
        assertThat(chunks.getFirst().heading()).isEqualTo("First section");
        for (Chunk chunk : chunks) {
            boolean first = chunk.content().contains("The first");
            boolean second = chunk.content().contains("The second");
            assertThat(first && second).as("chunk mixes two sections").isFalse();
            assertThat(chunk.heading()).isEqualTo(first ? "First section" : "Second section");
        }
        Chunk lastOfFirst = chunks.stream().filter(c -> c.heading().equals("First section")).reduce((a, b) -> b).orElseThrow();
        Chunk firstOfSecond = chunks.stream().filter(c -> c.heading().equals("Second section")).findFirst().orElseThrow();
        assertThat(firstOfSecond.charStart()).isGreaterThanOrEqualTo(lastOfFirst.charEnd());
        assertThat(firstOfSecond.content()).startsWith("Second section");
    }

    @Test
    void aChunkPrefersToEndAtAParagraphBoundary() {
        // Paragraph one fills ~80% of the target; paragraph two cannot be finished within the ceiling,
        // so the chunk stops after paragraph one instead of taking two sentences of paragraph two.
        String one = sentences("one", 5);
        String two = sentences("two", 9);
        ExtractedDocument document = new DocBuilder().paragraph(one).paragraph(two).build();
        assertThat(TOKENS.count(one)).isBetween((int) (TARGET * 0.6), TARGET - 9);

        List<Chunk> chunks = chunker.chunk(document);

        assertThat(chunks.getFirst().content()).isEqualTo(one);
    }

    @Test
    void aChunkRunsPastTheTargetToFinishAParagraph() {
        String one = sentences("one", 7); // 70 tokens: over the target of 60, under the ceiling of 80
        ExtractedDocument document = new DocBuilder().paragraph(one).paragraph(sentences("two", 7)).build();
        assertThat(TOKENS.count(one)).isBetween(TARGET + 1, MAX);

        assertThat(chunker.chunk(document).getFirst().content()).isEqualTo(one);
    }

    @Test
    void aShortSectionTailIsMergedInsteadOfBecomingAFragment() {
        String body = sentences("body", 7);
        ExtractedDocument document = new DocBuilder().paragraph(body).paragraph("A short closing remark.").build();

        List<Chunk> chunks = chunker.chunk(document);

        assertThat(chunks).hasSize(1);
        assertThat(chunks.getFirst().content()).endsWith("A short closing remark.");
    }

    @Test
    void aSentenceLargerThanAChunkIsCutBetweenWords() {
        String giant = "Start " + "word ".repeat(400).strip() + " end.";
        ExtractedDocument document = new DocBuilder().paragraph("Short intro sentence here. " + giant).build();

        List<Chunk> chunks = chunker.chunk(document);

        assertThat(chunks).hasSizeGreaterThan(4);
        for (Chunk chunk : chunks) {
            assertThat(TOKENS.count(chunk.content())).isLessThanOrEqualTo(MAX);
            assertThat(chunk.content()).doesNotStartWith(" ").doesNotEndWith(" ");
            assertThat(chunk.content().split(" ")).allMatch(word -> Set.of("Short", "intro", "sentence", "here.", "Start", "word", "end.").contains(word));
        }
        assertInvariants(document, chunks);
    }

    @Test
    void anUnbreakableTokenIsCutByCharactersRatherThanExceedingTheCeiling() {
        ExtractedDocument document = new DocBuilder().paragraph("x".repeat(5000)).build();

        List<Chunk> chunks = chunker.chunk(document);

        assertThat(chunks).allSatisfy(chunk -> assertThat(TOKENS.count(chunk.content())).isLessThanOrEqualTo(MAX));
        assertInvariants(document, chunks);
    }

    @Test
    void emptyDocumentYieldsNoChunks() {
        assertThat(chunker.chunk(new DocBuilder().build())).isEmpty();
    }

    @Test
    void ordinalsAreConsecutiveFromZero() {
        List<Chunk> chunks = chunker.chunk(randomDocument(new Random(7), false));
        for (int i = 0; i < chunks.size(); i++) {
            assertThat(chunks.get(i).ordinal()).isEqualTo(i);
        }
    }

    @Test
    void rejectsParametersThatCouldNotFitAnOverlapAndASentenceIntoOneChunk() {
        assertThatThrownBy(() -> new Chunker(100, 110, 20, 10, TOKENS)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Chunker(100, 300, 100, 10, TOKENS)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void embeddingTextPrependsTheHeadingOnlyWhenTheChunkDoesNotContainIt() {
        assertThat(Chunk.embeddingText("Hotel limits", "The limit is 140 EUR.")).isEqualTo("Hotel limits\nThe limit is 140 EUR.");
        assertThat(Chunk.embeddingText("Hotel limits", "Hotel limits\n\nThe limit is 140 EUR.")).isEqualTo("Hotel limits\n\nThe limit is 140 EUR.");
        assertThat(Chunk.embeddingText(null, "Plain text.")).isEqualTo("Plain text.");
    }

    // ---------------------------------------------------------------------------------------------

    private void assertInvariants(ExtractedDocument document, List<Chunk> chunks) {
        assertInvariants(document, chunks, TARGET, MAX, OVERLAP);
    }

    private static void assertInvariants(
            ExtractedDocument document, List<Chunk> chunks, int targetTokens, int maxTokens, int overlapTokens) {
        String text = document.text();
        boolean[] covered = new boolean[text.length()];
        // Offsets where a chunk may start or end: sentence boundaries, plus anywhere inside a sentence
        // that is larger than a whole chunk and therefore has to be cut.
        boolean[] mayStart = new boolean[text.length() + 1];
        boolean[] mayEnd = new boolean[text.length() + 1];
        for (Block block : document.blocks()) {
            if (block.type() == BlockType.HEADING) {
                mayStart[block.start()] = true;
                mayEnd[block.end()] = true;
                continue;
            }
            for (SentenceSplitter.Span span : SentenceSplitter.split(text, block.start(), block.end())) {
                int end = span.start() + text.substring(span.start(), span.end()).stripTrailing().length();
                mayStart[span.start()] = true;
                mayEnd[end] = true;
                if (TOKENS.count(text.substring(span.start(), end)) > targetTokens) {
                    for (int i = span.start(); i <= end; i++) {
                        mayStart[i] = true;
                        mayEnd[i] = true;
                    }
                }
            }
        }

        Chunk previous = null;
        for (Chunk chunk : chunks) {
            assertThat(chunk.content()).as("content equals the text at its offsets")
                    .isEqualTo(text.substring(chunk.charStart(), chunk.charEnd()));
            assertThat(chunk.content()).isEqualTo(chunk.content().strip());
            assertThat(TOKENS.count(chunk.content())).as("token ceiling").isLessThanOrEqualTo(maxTokens);
            assertThat(chunk.tokenCount()).isGreaterThanOrEqualTo(TOKENS.count(chunk.content()));
            assertThat(chunk.pageStart()).isEqualTo(document.pageAt(chunk.charStart()));
            assertThat(chunk.pageEnd()).isEqualTo(document.pageAt(chunk.charEnd() - 1));
            assertThat(chunk.heading()).as("nearest heading").isEqualTo(lastHeadingBefore(document, chunk.charEnd()));
            assertThat(mayStart[chunk.charStart()]).as("chunk %d starts on a sentence start", chunk.ordinal()).isTrue();
            assertThat(mayEnd[chunk.charEnd()]).as("chunk %d ends on a sentence end", chunk.ordinal()).isTrue();
            for (int i = chunk.charStart(); i < chunk.charEnd(); i++) {
                covered[i] = true;
            }
            if (previous != null) {
                assertThat(chunk.charStart()).as("starts increase").isGreaterThan(previous.charStart());
                assertThat(chunk.charEnd()).as("ends increase").isGreaterThan(previous.charEnd());
                if (chunk.charStart() < previous.charEnd()) {
                    String shared = text.substring(chunk.charStart(), previous.charEnd());
                    assertThat(TOKENS.count(shared)).as("overlap budget").isLessThanOrEqualTo(overlapTokens);
                    assertThat(Objects.equals(chunk.heading(), previous.heading())).as("overlap stays inside a section").isTrue();
                    for (Block block : document.blocks()) {
                        if (block.type() == BlockType.HEADING) {
                            assertThat(block.start() >= chunk.charStart() && block.start() < previous.charEnd())
                                    .as("no heading inside an overlap").isFalse();
                        }
                    }
                }
            }
            previous = chunk;
        }
        for (int i = 0; i < covered.length; i++) {
            if (!Character.isWhitespace(text.charAt(i))) {
                assertThat(covered[i]).as("character %d (%s) is in some chunk", i, text.charAt(i)).isTrue();
            }
        }
    }

    private static String lastHeadingBefore(ExtractedDocument document, int offset) {
        String heading = null;
        for (Block block : document.blocks()) {
            if (block.type() == BlockType.HEADING && block.start() < offset) {
                heading = document.blockText(block);
            }
        }
        return heading;
    }

    /** "The {topic} item N is listed here plainly." repeated: ten estimated tokens per sentence. */
    private static String sentences(String topic, int count) {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < count; i++) {
            text.append("The %s item %d is listed here plainly. ".formatted(topic, i));
        }
        return text.toString().strip();
    }

    private static ExtractedDocument randomDocument(Random random, boolean oversizedSentences) {
        String[] words = {"river", "policy", "the", "a", "valve", "of", "replication", "approval", "is", "and",
                "kilometre", "temperature", "must", "before", "after", "e.g.", "Dr.", "3.5", "limit", "trail"};
        DocBuilder builder = new DocBuilder();
        int sections = 1 + random.nextInt(6);
        boolean leadingText = random.nextBoolean();
        for (int s = 0; s < sections; s++) {
            if (s > 0 || !leadingText) {
                builder.heading("Section " + s + " heading");
                if (random.nextInt(5) == 0) {
                    builder.heading("Subsection " + s + " heading");
                }
            }
            int paragraphs = random.nextInt(6) + (s == sections - 1 ? 0 : 1);
            for (int p = 0; p < paragraphs; p++) {
                StringBuilder paragraph = new StringBuilder();
                int sentenceCount = 1 + random.nextInt(8);
                for (int i = 0; i < sentenceCount; i++) {
                    int length = 2 + random.nextInt(oversizedSentences && random.nextInt(6) == 0 ? 150 : 22);
                    paragraph.append("Sentence");
                    for (int w = 0; w < length; w++) {
                        paragraph.append(' ').append(words[random.nextInt(words.length)]);
                    }
                    paragraph.append(random.nextInt(8) == 0 ? "? " : ". ");
                }
                builder.paragraph(paragraph.toString().strip());
                if (random.nextInt(4) == 0) {
                    builder.newPage();
                }
            }
        }
        return builder.build();
    }
}
