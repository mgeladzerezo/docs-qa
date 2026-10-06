package io.github.mgeladzerezo.docsqa.retrieval;

import io.github.mgeladzerezo.docsqa.embedding.Vectors;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Hybrid search in one SQL statement: nearest neighbours by cosine distance (HNSW index), full-text
 * matches by {@code ts_rank_cd} (GIN index), fused with reciprocal rank fusion.
 *
 * <p>The statement is built from three fixed pieces so that vector-only and keyword-only search are
 * the same query with one ranking switched off, which is what the evaluation compares. The README
 * walks through the SQL; the comments here cover the non-obvious choices.
 */
@Repository
public class SearchRepository {

    public enum Mode { VECTOR, KEYWORD, HYBRID }

    /**
     * Restricts a search. Both parts are optional and combine with AND.
     *
     * @param documentIds only these documents, or empty for all
     * @param collection  only this collection, or null for all
     */
    public record Filter(List<UUID> documentIds, String collection) {

        public static final Filter NONE = new Filter(List.of(), null);

        public Filter {
            documentIds = documentIds == null ? List.of() : List.copyOf(documentIds);
            collection = collection == null || collection.isBlank() ? null : collection;
        }
    }

    /**
     * The inner ORDER BY ... LIMIT has exactly the shape pgvector needs to answer from the HNSW index:
     * ordered by the distance operator of the index's operator class, with a LIMIT, and nothing wrapped
     * around the operator. Ranks are assigned outside, on the few rows that survive.
     */
    private static final String VECTOR_HITS = """
            SELECT id, row_number() OVER (ORDER BY distance, id) AS rank
            FROM (
                SELECT c.id, c.embedding <=> CAST(:queryVector AS vector) AS distance
                FROM chunk c
                JOIN document d ON d.id = c.document_id
                WHERE d.status = 'READY' AND c.embedding IS NOT NULL%s
                ORDER BY c.embedding <=> CAST(:queryVector AS vector)
                LIMIT :candidates
            ) nearest
            """;

    /**
     * plainto_tsquery normalises the question (stemming, stop words) but ANDs the terms, and a chunk
     * rarely contains every word of a natural-language question. Rewriting '&' to '|' turns it into
     * "any of these terms"; ts_rank_cd then ranks chunks that match more terms, closer together and
     * in the heading (weight A) first.
     */
    private static final String KEYWORD_HITS = """
            SELECT id, score, row_number() OVER (ORDER BY score DESC, id) AS rank
            FROM (
                SELECT c.id, ts_rank_cd(c.content_tsv, q.terms) AS score
                FROM chunk c
                JOIN document d ON d.id = c.document_id
                CROSS JOIN (
                    SELECT to_tsquery('english', replace(plainto_tsquery('english', :queryText)::text, '&', '|')) AS terms
                ) q
                WHERE c.content_tsv @@ q.terms AND d.status = 'READY' AND c.embedding IS NOT NULL%s
                ORDER BY score DESC, c.id
                LIMIT :candidates
            ) matched
            """;

    private static final String NO_VECTOR_HITS = "SELECT NULL::bigint AS id, NULL::bigint AS rank WHERE false\n";
    private static final String NO_KEYWORD_HITS = "SELECT NULL::bigint AS id, NULL::real AS score, NULL::bigint AS rank WHERE false\n";

    /**
     * Reciprocal rank fusion: each ranking contributes 1 / (k + rank), a chunk missing from a ranking
     * contributes nothing. Only ranks are combined, so the incomparable scales of cosine distance and
     * ts_rank_cd never meet. The FULL OUTER JOIN keeps chunks found by only one of the two.
     */
    private static final String FUSION = """
            WITH vector_hits AS (
            %s),
            keyword_hits AS (
            %s)
            SELECT c.id, c.document_id, d.filename, d.collection, c.ordinal, c.heading, c.content,
                   c.page_start, c.page_end, c.char_start, c.char_end,
                   v.rank::int AS vector_rank,
                   k.rank::int AS keyword_rank,
                   k.score::float8 AS keyword_score,
                   1 - (c.embedding <=> CAST(:queryVector AS vector)) AS similarity,
                   COALESCE(1.0 / (:rrfK + v.rank), 0) + COALESCE(1.0 / (:rrfK + k.rank), 0) AS score,
                   c.embedding::text AS embedding
            FROM vector_hits v
            FULL OUTER JOIN keyword_hits k ON k.id = v.id
            JOIN chunk c ON c.id = COALESCE(v.id, k.id)
            JOIN document d ON d.id = c.document_id
            ORDER BY score DESC, c.id
            LIMIT :limit
            """;

    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;

    public SearchRepository(JdbcClient jdbc, TransactionTemplate transactions) {
        this.jdbc = jdbc;
        this.transactions = transactions;
    }

    /**
     * @param candidates rows taken from each ranking before fusion
     * @param limit      fused rows returned
     * @param rrfK       the k of reciprocal rank fusion; larger values flatten the advantage of top ranks
     * @param efSearch   HNSW candidate list size; must be at least {@code candidates} to return that many
     */
    public List<RetrievedChunk> search(
            Mode mode, String queryText, float[] queryVector, Filter filter, int candidates, int limit, int rrfK, int efSearch) {
        String sql = sql(mode, filter);
        return transactions.execute(_ -> {
            // Transaction-scoped settings (third argument true = SET LOCAL). With a document or
            // collection filter the index returns neighbours that the filter then discards;
            // iterative scan makes pgvector keep walking the graph until LIMIT rows have passed.
            jdbc.sql("SELECT set_config('hnsw.ef_search', :ef, true), set_config('hnsw.iterative_scan', 'strict_order', true)")
                    .param("ef", Integer.toString(Math.max(efSearch, candidates)))
                    .query((rs, _) -> rs.getString(1))
                    .single();
            JdbcClient.StatementSpec statement = jdbc.sql(sql)
                    .param("queryVector", Vectors.toLiteral(queryVector))
                    .param("queryText", queryText)
                    .param("candidates", candidates)
                    .param("rrfK", rrfK)
                    .param("limit", limit);
            if (!filter.documentIds().isEmpty()) {
                statement = statement.param("documentIds", filter.documentIds());
            }
            if (filter.collection() != null) {
                statement = statement.param("collection", filter.collection());
            }
            return statement.query(SearchRepository::mapRow).list();
        });
    }

    /** The complete statement for a mode and filter, exposed so tests can EXPLAIN exactly what runs. */
    static String sql(Mode mode, Filter filter) {
        StringBuilder predicate = new StringBuilder();
        if (!filter.documentIds().isEmpty()) {
            predicate.append(" AND c.document_id IN (:documentIds)");
        }
        if (filter.collection() != null) {
            predicate.append(" AND d.collection = :collection");
        }
        String vectorHits = mode == Mode.KEYWORD ? NO_VECTOR_HITS : VECTOR_HITS.formatted(predicate);
        String keywordHits = mode == Mode.VECTOR ? NO_KEYWORD_HITS : KEYWORD_HITS.formatted(predicate);
        return FUSION.formatted(vectorHits.indent(4), keywordHits.indent(4));
    }

    private static RetrievedChunk mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new RetrievedChunk(
                rs.getLong("id"),
                rs.getObject("document_id", UUID.class),
                rs.getString("filename"),
                rs.getString("collection"),
                rs.getInt("ordinal"),
                rs.getString("heading"),
                rs.getString("content"),
                rs.getInt("page_start"),
                rs.getInt("page_end"),
                rs.getInt("char_start"),
                rs.getInt("char_end"),
                rs.getObject("vector_rank", Integer.class),
                rs.getObject("keyword_rank", Integer.class),
                rs.getObject("keyword_score", Double.class),
                rs.getDouble("similarity"),
                rs.getDouble("score"),
                Vectors.parse(rs.getString("embedding")));
    }
}
