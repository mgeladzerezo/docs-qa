package io.github.mgeladzerezo.docsqa.eval;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.mgeladzerezo.docsqa.config.AppProperties;
import io.github.mgeladzerezo.docsqa.document.DocumentService;
import io.github.mgeladzerezo.docsqa.retrieval.RetrievalService;
import io.github.mgeladzerezo.docsqa.retrieval.RetrievedChunk;
import io.github.mgeladzerezo.docsqa.retrieval.SearchRepository.Filter;
import io.github.mgeladzerezo.docsqa.retrieval.SearchRepository.Mode;
import io.github.mgeladzerezo.docsqa.support.PostgresTest;
import io.github.mgeladzerezo.docsqa.support.SampleLibrary;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.DoubleSummaryStatistics;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Retrieval quality on the generated corpus with the real all-MiniLM-L6-v2 model: recall@k and MRR for
 * vector-only, keyword-only and hybrid search, and how often the similarity threshold abstains.
 *
 * <p>Run with {@code ./mvnw -Peval verify}. The model (about 90 MB) is downloaded on first use. The
 * report is printed and written to {@code target/eval/report.md}. The assertions are deliberately
 * loose sanity floors; the point of the test is the table, which is reported as measured.
 */
@Tag("eval")
@SpringBootTest(properties = {
        "docsqa.embedding.provider=onnx",
        "docsqa.embedding.model-name=all-MiniLM-L6-v2",
        "docsqa.ingestion.workers=2",
        "docsqa.ingestion.max-concurrent-embedding-batches=2"
})
class RetrievalEvaluationTest extends PostgresTest {

    private static final int[] CUTOFFS = {1, 3, 5, 6};
    private static final int MRR_DEPTH = 10;

    /** One golden question; {@code file} is null when the corpus has no answer. */
    record Golden(String kind, String question, String file, Set<Integer> pages) {

        boolean answerable() {
            return file != null;
        }

        boolean isRelevant(RetrievedChunk chunk) {
            return answerable() && chunk.filename().equals(file)
                    && pages.stream().anyMatch(page -> page >= chunk.pageStart() && page <= chunk.pageEnd());
        }
    }

    /** What one ranking achieved on one question. */
    record Outcome(Golden golden, List<RetrievedChunk> ranking) {

        double recallAt(int k) {
            Set<Integer> covered = new java.util.HashSet<>();
            ranking.stream().limit(k).filter(golden::isRelevant).forEach(chunk -> golden.pages().stream()
                    .filter(page -> page >= chunk.pageStart() && page <= chunk.pageEnd())
                    .forEach(covered::add));
            return covered.size() / (double) golden.pages().size();
        }

        double reciprocalRank() {
            for (int i = 0; i < Math.min(MRR_DEPTH, ranking.size()); i++) {
                if (golden.isRelevant(ranking.get(i))) {
                    return 1.0 / (i + 1);
                }
            }
            return 0;
        }
    }

    @Autowired
    DocumentService documents;
    @Autowired
    RetrievalService retrieval;
    @Autowired
    AppProperties properties;
    @Autowired
    JdbcClient jdbc;

    @Test
    void measureRetrieval() throws IOException {
        jdbc.sql("TRUNCATE document CASCADE").update();
        long ingestStart = System.nanoTime();
        SampleLibrary.ingest(documents, Duration.ofMinutes(10));
        long ingestMillis = (System.nanoTime() - ingestStart) / 1_000_000;
        int chunkCount = jdbc.sql("SELECT count(*) FROM chunk").query(Integer.class).single();

        List<Golden> golden = loadGolden();
        List<Golden> answerable = golden.stream().filter(Golden::answerable).toList();
        List<Golden> unanswerable = golden.stream().filter(g -> !g.answerable()).toList();
        assertThat(answerable.size()).isGreaterThanOrEqualTo(25);
        assertThat(unanswerable).isNotEmpty();

        Map<String, List<Outcome>> byMethod = new LinkedHashMap<>();
        Map<Golden, RetrievalService.Result> hybridResults = new LinkedHashMap<>();
        long searchNanos = 0;
        int searches = 0;
        for (Mode mode : Mode.values()) {
            List<Outcome> outcomes = new ArrayList<>();
            for (Golden question : answerable) {
                long start = System.nanoTime();
                RetrievalService.Result result = retrieval.retrieve(question.question(), Filter.NONE, mode);
                searchNanos += System.nanoTime() - start;
                searches++;
                outcomes.add(new Outcome(question, result.candidates()));
                if (mode == Mode.HYBRID) {
                    hybridResults.put(question, result);
                }
            }
            byMethod.put(label(mode), outcomes);
        }
        byMethod.put("hybrid + MMR (the passages given to the model)", answerable.stream()
                .map(question -> new Outcome(question, hybridResults.get(question).selected()))
                .toList());

        StringBuilder report = new StringBuilder();
        report.append("Corpus: %d PDFs, %d chunks (ingested in %.1f s). Golden set: %d answerable, %d unanswerable questions.\n\n"
                .formatted(documents.list().size(), chunkCount, ingestMillis / 1000.0, answerable.size(), unanswerable.size()));
        report.append("Retrieval on the %d answerable questions (a chunk is relevant if it contains an expected page)\n\n".formatted(answerable.size()));
        report.append(table(byMethod));
        report.append("\nMRR@10 by question kind\n\n").append(kindTable(byMethod, answerable));
        report.append("\nQuestions where hybrid missed the expected page in its top 5\n\n");
        List<Outcome> hybrid = byMethod.get(label(Mode.HYBRID));
        hybrid.stream().filter(outcome -> outcome.recallAt(5) < 1).forEach(outcome -> report.append("- ")
                .append(outcome.golden().question()).append(" (expected ").append(outcome.golden().file())
                .append(" p").append(outcome.golden().pages()).append(")\n"));
        report.append(abstention(answerable, unanswerable));
        report.append("\nMean search time (embedding the query + SQL): %.1f ms over %d searches.\n"
                .formatted(searchNanos / 1e6 / searches, searches));
        System.out.println("\n===== RETRIEVAL EVALUATION =====\n" + report + "================================");
        Path out = Path.of("target", "eval", "report.md");
        Files.createDirectories(out.getParent());
        Files.writeString(out, report.toString(), StandardCharsets.UTF_8);

        // Sanity floors only: a broken pipeline would fall far below them, a real model is far above.
        assertThat(mean(hybrid, o -> o.recallAt(5))).isGreaterThan(0.6);
    }

    private String abstention(List<Golden> answerable, List<Golden> unanswerable) {
        double threshold = properties.retrieval().minSimilarity();
        StringBuilder out = new StringBuilder("\nAbstention (hybrid pipeline, similarity threshold %.2f)\n\n".formatted(threshold));
        DoubleSummaryStatistics answerableBest = new DoubleSummaryStatistics();
        DoubleSummaryStatistics unanswerableBest = new DoubleSummaryStatistics();
        long abstainedUnanswerable = 0;
        long abstainedAnswerable = 0;
        StringBuilder leaked = new StringBuilder();
        for (Golden question : unanswerable) {
            RetrievalService.Result result = retrieval.retrieve(question.question(), Filter.NONE);
            unanswerableBest.accept(result.bestSimilarity());
            if (result.confident()) {
                leaked.append("- %s (best similarity %.2f)\n".formatted(question.question(), result.bestSimilarity()));
            } else {
                abstainedUnanswerable++;
            }
        }
        for (Golden question : answerable) {
            RetrievalService.Result result = retrieval.retrieve(question.question(), Filter.NONE);
            answerableBest.accept(result.bestSimilarity());
            if (!result.confident()) {
                abstainedAnswerable++;
            }
        }
        out.append("| | questions | abstained | best similarity min / mean / max |\n|---|---|---|---|\n");
        out.append("| unanswerable | %d | %d (%.0f%%) | %.2f / %.2f / %.2f |\n".formatted(unanswerable.size(), abstainedUnanswerable,
                100.0 * abstainedUnanswerable / unanswerable.size(), unanswerableBest.getMin(), unanswerableBest.getAverage(), unanswerableBest.getMax()));
        out.append("| answerable | %d | %d (%.0f%%) | %.2f / %.2f / %.2f |\n".formatted(answerable.size(), abstainedAnswerable,
                100.0 * abstainedAnswerable / answerable.size(), answerableBest.getMin(), answerableBest.getAverage(), answerableBest.getMax()));
        if (!leaked.isEmpty()) {
            out.append("\nUnanswerable questions that passed the threshold (the model would be asked, and has to say \"not found\" itself)\n\n")
                    .append(leaked);
        }
        return out.toString();
    }

    private static String table(Map<String, List<Outcome>> byMethod) {
        StringBuilder table = new StringBuilder("| method |");
        for (int k : CUTOFFS) {
            table.append(" recall@%d |".formatted(k));
        }
        table.append(" MRR@10 |\n|---|").append("---|".repeat(CUTOFFS.length + 1)).append('\n');
        byMethod.forEach((name, outcomes) -> {
            table.append("| ").append(name).append(" |");
            for (int k : CUTOFFS) {
                table.append(" %.3f |".formatted(mean(outcomes, outcome -> outcome.recallAt(k))));
            }
            table.append(" %.3f |\n".formatted(mean(outcomes, Outcome::reciprocalRank)));
        });
        return table.toString();
    }

    private static String kindTable(Map<String, List<Outcome>> byMethod, List<Golden> answerable) {
        List<String> kinds = answerable.stream().map(Golden::kind).distinct().toList();
        StringBuilder table = new StringBuilder("| method |");
        kinds.forEach(kind -> table.append(" %s (%d) |".formatted(kind, answerable.stream().filter(g -> g.kind().equals(kind)).count())));
        table.append("\n|---|").append("---|".repeat(kinds.size())).append('\n');
        byMethod.forEach((name, outcomes) -> {
            if (name.contains("MMR")) {
                return;
            }
            table.append("| ").append(name).append(" |");
            for (String kind : kinds) {
                table.append(" %.3f |".formatted(mean(outcomes.stream().filter(o -> o.golden().kind().equals(kind)).toList(), Outcome::reciprocalRank)));
            }
            table.append('\n');
        });
        return table.toString();
    }

    private static double mean(List<Outcome> outcomes, java.util.function.ToDoubleFunction<Outcome> metric) {
        return outcomes.stream().mapToDouble(metric).average().orElse(0);
    }

    private static String label(Mode mode) {
        return switch (mode) {
            case VECTOR -> "vector only (HNSW, cosine)";
            case KEYWORD -> "keyword only (tsvector, ts_rank_cd)";
            case HYBRID -> "hybrid (RRF, k=60)";
        };
    }

    private static List<Golden> loadGolden() throws IOException {
        try (var in = RetrievalEvaluationTest.class.getResourceAsStream("/eval/golden.tsv")) {
            String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            return text.lines()
                    .filter(line -> !line.isBlank() && !line.startsWith("#"))
                    .map(line -> line.split("\t", -1))
                    .map(parts -> new Golden(parts[0], parts[1], parts[2].isBlank() ? null : parts[2],
                            parts[3].isBlank() ? Set.of() : java.util.Arrays.stream(parts[3].split(","))
                                    .map(s -> Integer.parseInt(s.strip())).collect(Collectors.toSet())))
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
