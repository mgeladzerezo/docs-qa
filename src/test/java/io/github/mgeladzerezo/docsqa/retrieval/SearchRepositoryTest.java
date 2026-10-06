package io.github.mgeladzerezo.docsqa.retrieval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import io.github.mgeladzerezo.docsqa.embedding.Vectors;
import io.github.mgeladzerezo.docsqa.retrieval.SearchRepository.Filter;
import io.github.mgeladzerezo.docsqa.retrieval.SearchRepository.Mode;
import io.github.mgeladzerezo.docsqa.support.FakeEmbeddingConfiguration;
import io.github.mgeladzerezo.docsqa.support.HashingEmbeddingModel;
import io.github.mgeladzerezo.docsqa.support.PostgresTest;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The hybrid SQL against real pgvector: ranking, the exact fusion arithmetic, filters and whether the
 * statement can be answered from the HNSW and GIN indexes. Rows are inserted directly with chosen
 * vectors, so every expected rank can be derived by hand.
 */
@SpringBootTest(properties = "docsqa.ingestion.workers=0")
@Import(FakeEmbeddingConfiguration.class)
class SearchRepositoryTest extends PostgresTest {

    private static final int RRF_K = 60;

    @Autowired
    SearchRepository repository;
    @Autowired
    JdbcClient jdbc;
    @Autowired
    TransactionTemplate transactions;

    @BeforeEach
    void emptyLibrary() {
        jdbc.sql("TRUNCATE document CASCADE").update();
    }

    @Test
    void vectorSearchOrdersByCosineSimilarityAndReportsIt() {
        UUID document = document("manual.pdf", "default", "READY");
        chunk(document, 0, null, "far", axis(0.1f, 1f));
        chunk(document, 1, null, "nearest", axis(1f, 0.05f));
        chunk(document, 2, null, "middle", axis(1f, 1f));
        float[] query = axis(1f, 0f);

        List<RetrievedChunk> hits = repository.search(Mode.VECTOR, "ignored", query, Filter.NONE, 10, 10, RRF_K, 40);

        assertThat(hits).extracting(RetrievedChunk::content).containsExactly("nearest", "middle", "far");
        assertThat(hits).extracting(RetrievedChunk::vectorRank).containsExactly(1, 2, 3);
        assertThat(hits).extracting(RetrievedChunk::keywordRank).containsOnlyNulls();
        assertThat(hits.get(1).similarity()).isCloseTo(Math.cos(Math.PI / 4), within(1e-5));
        assertThat(hits.get(0).similarity()).isCloseTo(Vectors.cosine(query, axis(1f, 0.05f)), within(1e-5));
        assertThat(hits.get(0).embedding()).hasSize(HashingEmbeddingModel.DIMENSIONS);
    }

    @Test
    void keywordSearchStemsMatchesAnyTermAndWeighsHeadings() {
        UUID document = document("manual.pdf", "default", "READY");
        chunk(document, 0, "Cleaning", "Rinse the portafilter after each session.", axis(1f, 0f));
        chunk(document, 1, "Maintenance", "The machine is descaled with citric acid every two months.", axis(1f, 0f));
        chunk(document, 2, "Descaling", "Run the programme when the orange light blinks.", axis(1f, 0f));
        chunk(document, 3, "Warranty", "Two years for household use.", axis(1f, 0f));

        List<RetrievedChunk> hits = repository.search(
                Mode.KEYWORD, "How often should I be descaling my unicorn?", axis(1f, 0f), Filter.NONE, 10, 10, RRF_K, 40);

        // "descaling" is stemmed to match "descaled"; "unicorn" matches nothing and does not block the
        // match; the chunk with the term in its heading ranks first.
        assertThat(hits).extracting(RetrievedChunk::heading).containsExactly("Descaling", "Maintenance");
        assertThat(hits).extracting(RetrievedChunk::keywordRank).containsExactly(1, 2);
        assertThat(hits).extracting(RetrievedChunk::vectorRank).containsOnlyNulls();
        assertThat(hits.get(0).keywordScore()).isGreaterThan(hits.get(1).keywordScore());
    }

    @Test
    void aQuestionMadeOfStopWordsHasNoKeywordHitsAndDoesNotFail() {
        UUID document = document("manual.pdf", "default", "READY");
        chunk(document, 0, null, "Who is it and what is that?", axis(1f, 0f));

        assertThat(repository.search(Mode.KEYWORD, "what is it?", axis(1f, 0f), Filter.NONE, 10, 10, RRF_K, 40)).isEmpty();
        assertThat(repository.search(Mode.HYBRID, "what is it?", axis(1f, 0f), Filter.NONE, 10, 10, RRF_K, 40)).hasSize(1);
    }

    @Test
    void hybridScoreIsTheSumOfReciprocalRanks() {
        UUID document = document("runbook.pdf", "default", "READY");
        // Vector order for the query (1,0): onlyVector, both, weak, onlyKeyword.
        chunk(document, 0, null, "Nothing in common with the question words.", axis(1f, 0.01f));
        chunk(document, 1, null, "The failover failover procedure for the failover drill.", axis(1f, 0.3f));
        chunk(document, 2, null, "Unrelated filler.", axis(1f, 1f));
        chunk(document, 3, null, "A failover is announced beforehand.", axis(0.1f, 1f));

        // candidates = 3: the fourth-nearest chunk is cut from the vector ranking and is keyword-only.
        List<RetrievedChunk> hits = repository.search(Mode.HYBRID, "failover", axis(1f, 0f), Filter.NONE, 3, 10, RRF_K, 40);

        assertThat(hits).extracting(RetrievedChunk::ordinal).containsExactly(1, 0, 3, 2);
        RetrievedChunk both = hits.get(0);
        assertThat(both.vectorRank()).isEqualTo(2);
        assertThat(both.keywordRank()).isEqualTo(1);
        assertThat(both.score()).isCloseTo(1.0 / (RRF_K + 2) + 1.0 / (RRF_K + 1), within(1e-9));
        RetrievedChunk onlyVector = hits.get(1);
        assertThat(onlyVector.vectorRank()).isEqualTo(1);
        assertThat(onlyVector.keywordRank()).isNull();
        assertThat(onlyVector.score()).isCloseTo(1.0 / (RRF_K + 1), within(1e-9));
        RetrievedChunk onlyKeyword = hits.get(2);
        assertThat(onlyKeyword.vectorRank()).isNull();
        assertThat(onlyKeyword.keywordRank()).isEqualTo(2);
        assertThat(onlyKeyword.score()).isCloseTo(1.0 / (RRF_K + 2), within(1e-9));
        assertThat(onlyKeyword.similarity()).as("similarity is reported even for keyword-only hits")
                .isCloseTo(Vectors.cosine(axis(1f, 0f), axis(0.1f, 1f)), within(1e-5));
        assertThat(hits.get(3).score()).isCloseTo(1.0 / (RRF_K + 3), within(1e-9));
    }

    @Test
    void filtersRestrictBothRankingsAndUnfinishedDocumentsAreInvisible() {
        UUID travel = document("travel.pdf", "policies", "READY");
        UUID manual = document("manual.pdf", "manuals", "READY");
        UUID embedding = document("half-done.pdf", "manuals", "EMBEDDING");
        chunk(travel, 0, null, "The hotel limit is 140 EUR.", axis(1f, 0f));
        chunk(manual, 0, null, "The hotel lobby has an espresso machine.", axis(1f, 0.1f));
        chunk(manual, 1, null, "Still waiting for its embedding, mentions hotel.", null);
        chunk(embedding, 0, null, "Not ready yet, mentions hotel.", axis(1f, 0f));

        // Neither the unfinished document nor the chunk without an embedding shows up, in any mode.
        assertThat(search(Mode.HYBRID, Filter.NONE)).extracting(RetrievedChunk::filename)
                .containsExactly("travel.pdf", "manual.pdf");
        assertThat(search(Mode.VECTOR, Filter.NONE)).extracting(RetrievedChunk::filename)
                .containsExactly("travel.pdf", "manual.pdf");
        assertThat(search(Mode.KEYWORD, Filter.NONE)).extracting(RetrievedChunk::filename)
                .containsExactlyInAnyOrder("travel.pdf", "manual.pdf");
        assertThat(search(Mode.HYBRID, new Filter(List.of(travel), null))).extracting(RetrievedChunk::filename)
                .containsExactly("travel.pdf");
        assertThat(search(Mode.HYBRID, new Filter(List.of(), "manuals"))).extracting(RetrievedChunk::filename)
                .containsExactly("manual.pdf");
        assertThat(search(Mode.KEYWORD, new Filter(List.of(travel, manual), "policies"))).extracting(RetrievedChunk::filename)
                .containsExactly("travel.pdf");
        assertThat(search(Mode.HYBRID, new Filter(List.of(embedding), null))).isEmpty();
        assertThat(search(Mode.HYBRID, new Filter(List.of(), "no-such-collection"))).isEmpty();
    }

    @Test
    void aFilteredVectorSearchStillReturnsTheRequestedNumberOfRows() {
        UUID big = document("big.pdf", "default", "READY");
        UUID small = document("small.pdf", "default", "READY");
        randomChunks(big, 3000);
        randomChunks(small, 40);
        jdbc.sql("ANALYZE document, chunk").update();

        // The nearest neighbours overall are almost all in big.pdf; the filter must not starve the result.
        List<RetrievedChunk> hits = repository.search(
                Mode.VECTOR, "ignored", HashingEmbeddingModel.vectorOf("any query text at all"),
                new Filter(List.of(small), null), 25, 25, RRF_K, 40);

        assertThat(hits).hasSize(25);
        assertThat(hits).extracting(RetrievedChunk::documentId).containsOnly(small);
        assertThat(hits).extracting(RetrievedChunk::similarity).isSortedAccordingTo((a, b) -> Double.compare(b, a));
    }

    @Test
    void theStatementIsAnsweredFromTheHnswAndGinIndexes() {
        UUID document = document("big.pdf", "default", "READY");
        randomChunks(document, 3000);
        // Statistics on both tables, as autovacuum provides them. Without statistics on "document" the
        // planner assumes that status = 'READY' keeps 0.5% of the chunks and sorts those few instead.
        jdbc.sql("ANALYZE document, chunk").update();

        String plan = explain(Mode.HYBRID, Filter.NONE, false);

        assertThat(plan).as(plan).contains("Index Scan using chunk_embedding_hnsw");
        assertThat(plan).as(plan).contains("Bitmap Index Scan on chunk_content_tsv_gin");
    }

    @Test
    void theVectorBranchCanUseTheHnswIndexUnderADocumentFilter() {
        UUID document = document("big.pdf", "default", "READY");
        randomChunks(document, 3000);
        jdbc.sql("ANALYZE document, chunk").update();

        // With a selective filter the planner may legitimately prefer the btree on document_id.
        // Disabling the alternatives shows that the filtered statement is still index-compatible.
        String plan = explain(Mode.VECTOR, new Filter(List.of(document), "default"), true);

        assertThat(plan).as(plan).contains("Index Scan using chunk_embedding_hnsw");
    }

    // ---------------------------------------------------------------------------------------------

    private List<RetrievedChunk> search(Mode mode, Filter filter) {
        return repository.search(mode, "hotel", axis(1f, 0f), filter, 10, 10, RRF_K, 40);
    }

    private String explain(Mode mode, Filter filter, boolean indexOnly) {
        return transactions.execute(_ -> {
            if (indexOnly) {
                jdbc.sql("SET LOCAL enable_seqscan = off").update();
                jdbc.sql("SET LOCAL enable_bitmapscan = off").update();
                jdbc.sql("SET LOCAL enable_sort = off").update();
            }
            JdbcClient.StatementSpec statement = jdbc.sql("EXPLAIN " + SearchRepository.sql(mode, filter))
                    .param("queryVector", Vectors.toLiteral(HashingEmbeddingModel.vectorOf("replication lag threshold")))
                    .param("queryText", "subject17 or subject42")
                    .param("candidates", 30)
                    .param("rrfK", RRF_K)
                    .param("limit", 20);
            if (!filter.documentIds().isEmpty()) {
                statement = statement.param("documentIds", filter.documentIds());
            }
            if (filter.collection() != null) {
                statement = statement.param("collection", filter.collection());
            }
            return String.join("\n", statement.query(String.class).list());
        });
    }

    private UUID document(String filename, String collection, String status) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO document (id, filename, collection, content_hash, size_bytes, storage_path, status,
                                              embedding_model, embedding_dimension)
                        VALUES (:id, :filename, :collection, :hash, 1, 'unused', :status, 'test-hashing-384', 384)
                        """)
                .param("id", id)
                .param("filename", filename)
                .param("collection", collection)
                .param("hash", (id.toString() + id).replace("-", "").substring(0, 64))
                .param("status", status)
                .update();
        return id;
    }

    private void chunk(UUID document, int ordinal, String heading, String content, float[] embedding) {
        jdbc.sql("""
                        INSERT INTO chunk (document_id, ordinal, content, heading, page_start, page_end,
                                           char_start, char_end, token_count, embedding)
                        VALUES (:document, :ordinal, :content, :heading, 1, 1, 0, :length, 1, CAST(:embedding AS vector))
                        """)
                .param("document", document)
                .param("ordinal", ordinal)
                .param("content", content)
                .param("heading", heading)
                .param("length", content.length())
                .param("embedding", embedding == null ? null : Vectors.toLiteral(embedding))
                .update();
    }

    /**
     * Chunks with random vectors and a small vocabulary, generated inside the database. The reference
     * to g in the subquery makes it correlated, so every row gets its own vector.
     */
    private void randomChunks(UUID document, int count) {
        jdbc.sql("""
                        INSERT INTO chunk (document_id, ordinal, content, page_start, page_end, char_start, char_end,
                                           token_count, embedding)
                        SELECT :document, g, 'filler text number ' || g || ' about subject' || (g % 300), 1, 1, 0, 10, 5,
                               (SELECT array_agg(random() - 0.5)::real[]::vector(384) FROM generate_series(1, 384) WHERE g > 0)
                        FROM generate_series(1, :count) g
                        """)
                .param("document", document)
                .param("count", count)
                .update();
    }

    /** A 384-dimensional vector that only uses the first two axes, so angles are easy to reason about. */
    private static float[] axis(float x, float y) {
        float[] vector = new float[HashingEmbeddingModel.DIMENSIONS];
        vector[0] = x;
        vector[1] = y;
        return vector;
    }
}
