-- Knowledge base schema: documents, chunks, and pgvector index.

CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE document (
    id          UUID        PRIMARY KEY,
    title       TEXT        NOT NULL,
    source_type TEXT        NOT NULL,
    status      TEXT        NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE document_chunk (
    id          UUID         PRIMARY KEY,
    document_id UUID         NOT NULL REFERENCES document (id) ON DELETE CASCADE,
    chunk_index INT          NOT NULL,
    content     TEXT         NOT NULL,
    embedding   vector(1536),
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX document_chunk_document_id_idx
    ON document_chunk (document_id);

-- Approximate nearest-neighbour index for cosine similarity search.
CREATE INDEX document_chunk_embedding_idx
    ON document_chunk USING ivfflat (embedding vector_cosine_ops)
    WITH (lists = 100);
