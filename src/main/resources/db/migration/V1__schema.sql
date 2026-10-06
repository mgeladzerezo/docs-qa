CREATE EXTENSION IF NOT EXISTS vector;

-- One row per uploaded PDF. The row is also the ingestion job: status is the stage, and
-- locked_by/locked_until form a lease so that a crashed worker's document is picked up again.
CREATE TABLE document (
    id                  uuid PRIMARY KEY,
    filename            text        NOT NULL,
    collection          text        NOT NULL DEFAULT 'default',
    content_hash        char(64)    NOT NULL UNIQUE,
    size_bytes          bigint      NOT NULL,
    storage_path        text        NOT NULL,
    page_count          int,
    status              text        NOT NULL
        CHECK (status IN ('QUEUED', 'EXTRACTING', 'CHUNKING', 'EMBEDDING', 'READY', 'FAILED')),
    failure_reason      text,
    chunks_total        int         NOT NULL DEFAULT 0,
    chunks_embedded     int         NOT NULL DEFAULT 0,
    embedding_model     text        NOT NULL,
    embedding_dimension int         NOT NULL,
    attempts            int         NOT NULL DEFAULT 0,
    locked_by           text,
    locked_until        timestamptz,
    sample              boolean     NOT NULL DEFAULT false,
    created_at          timestamptz NOT NULL DEFAULT now(),
    updated_at          timestamptz NOT NULL DEFAULT now()
);

-- Workers scan for claimable work; terminal documents are the vast majority and stay out of this index.
CREATE INDEX document_claimable_idx ON document (created_at)
    WHERE status IN ('QUEUED', 'EXTRACTING', 'CHUNKING', 'EMBEDDING');

-- Cleaned text of each page. char_start is the page's offset in the document text, so a chunk's
-- [char_start, char_end) can be mapped back to a position inside a page for highlighting.
CREATE TABLE document_page (
    document_id uuid NOT NULL REFERENCES document (id) ON DELETE CASCADE,
    page_number int  NOT NULL,
    char_start  int  NOT NULL,
    content     text NOT NULL,
    PRIMARY KEY (document_id, page_number)
);

CREATE TABLE chunk (
    id          bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    document_id uuid NOT NULL REFERENCES document (id) ON DELETE CASCADE,
    ordinal     int  NOT NULL,
    content     text NOT NULL,
    heading     text,
    page_start  int  NOT NULL,
    page_end    int  NOT NULL,
    char_start  int  NOT NULL,
    char_end    int  NOT NULL,
    token_count int  NOT NULL,
    -- NULL until the embedding stage reaches this chunk; the dimension is fixed by the column type.
    embedding   vector(384),
    -- Heading terms weigh more (A) than body terms (B) in ts_rank_cd.
    content_tsv tsvector GENERATED ALWAYS AS (
        setweight(to_tsvector('english', coalesce(heading, '')), 'A') ||
        setweight(to_tsvector('english', content), 'B')
    ) STORED,
    UNIQUE (document_id, ordinal),
    CHECK (char_end > char_start),
    CHECK (page_end >= page_start)
);

-- Approximate nearest neighbour search under cosine distance (the <=> operator).
CREATE INDEX chunk_embedding_hnsw ON chunk USING hnsw (embedding vector_cosine_ops)
    WITH (m = 16, ef_construction = 64);

CREATE INDEX chunk_content_tsv_gin ON chunk USING gin (content_tsv);
