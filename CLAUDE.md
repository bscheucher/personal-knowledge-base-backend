# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

A personal RAG (Retrieval-Augmented Generation) knowledge base backend: documents are uploaded
(PDF/URL/text), chunked, embedded via OpenAI, and stored as vectors in PostgreSQL (pgvector).
Chat questions retrieve the most relevant chunks and stream a grounded LLM answer over SSE.

Stack: Spring Boot 3.5, Java 21, Spring AI 1.1, PostgreSQL 16 + pgvector, Flyway, Gradle (Kotlin DSL).
The React frontend described in the spec is **not yet built** — this repo is backend only.

## Project layout gotcha

The Gradle project lives in the `base/` subdirectory, **not** the repo root. Run all `./gradlew`
commands from `base/`. Source root is `base/src/main/java/personal/knowledge/base/`.

## Commands

All from `base/`:

```bash
# Run the backend (OpenAI key required for live embeddings/chat)
OPENAI_API_KEY=$(cat ../.openai_key) ./gradlew bootRun

# Full test suite — requires the pgvector container running (see below)
./gradlew test

# Single test class
./gradlew test --tests '*ChunkingServiceTest'

# The real-OpenAI end-to-end test (skipped unless OPENAI_API_KEY is set; needs a funded key)
OPENAI_API_KEY=$(cat ../.openai_key) ./gradlew test --tests '*IngestServiceIntegrationTest'

./gradlew build      # compile + test + assemble
```

## Local environment

- **pgvector runs on host port 5433**, not the default 5432 (a local Postgres occupies 5432). The
  `application.yaml` default `DB_URL` points to 5433. Start it with:
  ```bash
  docker run -d --name pgvector \
    -e POSTGRES_DB=knowledgebase -e POSTGRES_USER=kb -e POSTGRES_PASSWORD=kb \
    -p 5433:5432 pgvector/pgvector:pg16
  ```
- OpenAI key is read from the gitignored `.openai_key` file at the repo root.
- Most tests need the container; stubbed integration tests (deterministic fake AI models) run the
  full ingest/retrieval pipeline against **real pgvector with no API key**. Only
  `IngestServiceIntegrationTest` calls live OpenAI and self-gates on the env var.

## Architecture

Two pipelines share one database, split into packages by concern:

- **`ingest/`** — `IngestService` orchestrates extract → chunk → embed → store. Text extraction is
  per-source (Jsoup for URLs, PDFBox `Loader` for PDFs, raw passthrough for text). `ChunkingService`
  does fixed-size 500-char windows with 50-char overlap (step 450). `EmbeddingService` wraps Spring
  AI `EmbeddingModel`.
- **`chat/`** — `ChatService` embeds the question, calls `ChunkRepository.findNearest` (top-5),
  builds a grounded system prompt, and streams tokens. `LlmClient` wraps `ChatModel` and returns a
  `Flux<String>`. `ChatController` exposes `GET /api/chat/stream?question=...` as SSE.
- **`document/`** — `DocumentController` + request/response DTOs (Bean Validation) for the upload /
  url / text / list / delete endpoints.
- **`domain/`** — JPA entities and enums. `DocumentChunk.embedding` maps `vector(1536)` ↔ `float[]`
  via `@JdbcTypeCode(SqlTypes.VECTOR)` + `@Array(length = 1536)` (hibernate-vector).
- **`repository/`** — Spring Data repos. `ChunkRepository.findNearest` is a **native** pgvector
  cosine query (`embedding <=> CAST(:embedding AS vector)`); a `float[]` overload formats the vector
  into a pgvector literal string by hand (`toVectorLiteral`).
- **`web/`** — `GlobalExceptionHandler` returns RFC-7807 `ProblemDetail`: 404 not-found, 422 ingest
  failure, 400 validation.

### Things that bite

- **Ingest status writes commit independently of the orchestration body** so a failed embedding/parse
  still persists the document as `ERROR` (status flow: `PENDING → PROCESSING → READY | ERROR`). When
  touching `IngestService.ingest`, preserve this error-path save.
- **Schema is owned by Flyway, not Hibernate.** `ddl-auto: validate` and pgvector
  `initialize-schema: false` — schema changes go in a new `db/migration/V__*.sql` file, and entities
  must match or startup fails validation.
- **`hibernate-vector` and `jsoup` versions are pinned explicitly** in `build.gradle.kts` because the
  Spring Boot BOM does not manage them. `hibernate-vector` must match the managed Hibernate version.
- Embedding model `text-embedding-3-small` produces **1536 dims** — this number is hardcoded in the
  migration, the entity `@Array(length)`, and pgvector config; change all three together.

## Reference docs

- `base/src/docs/knowledge-base-spec.md` — full project spec (incl. the planned React frontend).
- `base/src/docs/implementation-progress.md` — what's built and verified vs. out of scope.
- `base/README.md` — quickstart, API table with curl examples, configuration env vars.