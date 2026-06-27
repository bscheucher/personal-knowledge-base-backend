# Knowledge base — implementation progress

_Last updated: 2026-06-27_

Tracks how the backend implementation maps to [`knowledge-base-spec.md`](./knowledge-base-spec.md).
Scope so far is the **Spring Boot backend**; the React frontend is not yet started.

---

## Status at a glance

| Layer | Status | Notes |
|---|---|---|
| Build & dependencies | ✅ Done | Spring Boot 3.5.16, Java 21, Spring AI 1.1.8 |
| Configuration | ✅ Done | `application.yaml` with datasource, JPA, Flyway, Spring AI |
| Database & migration | ✅ Done & verified | Flyway `V1__init.sql` applied to real pgvector |
| Data model (entities/repos) | ✅ Done & verified | `vector(1536)` ↔ `float[]` via hibernate-vector |
| Ingest layer | ✅ Done & verified | Text / URL / PDF |
| Query layer (RAG chat) | ✅ Done & verified | Retrieval + grounded prompt + SSE streaming |
| REST API | ✅ Done & verified | Document + chat endpoints, live smoke-tested |
| Frontend (React) | ⛔ Not started | Out of backend scope |

Verified **offline** (stubbed AI models) and **live** (real OpenAI calls) — see [Verification](#verification).

---

## What was built

### Build & dependencies (`build.gradle.kts`)
Added beyond the Spring Initializr baseline:
- `org.hibernate.orm:hibernate-vector:6.6.53.Final` — maps `vector(1536)` to `float[]`
- `org.apache.pdfbox:pdfbox:3.0.5` — PDF text extraction
- `org.jsoup:jsoup:1.18.3` — URL text extraction

> Note: `hibernate-vector` and `jsoup` are **not** managed by the Spring Boot BOM, so their
> versions are pinned explicitly (`hibernate-vector` matches the managed Hibernate `6.6.53.Final`).

### Configuration (`src/main/resources/application.yaml`)
Datasource (env-var driven with local defaults), JPA `ddl-auto: validate`, `open-in-view: false`,
Flyway enabled, and the full `spring.ai` block (OpenAI embedding/chat models, pgvector settings).

### Database (`src/main/resources/db/migration/V1__init.sql`)
- `CREATE EXTENSION vector`
- `document` and `document_chunk` tables (FK `ON DELETE CASCADE`)
- ivfflat cosine index `document_chunk_embedding_idx` + FK lookup index

### Domain & repositories
| Type | Location | Purpose |
|---|---|---|
| `Document`, `DocumentChunk` | `domain/` | JPA entities; embedding via `@JdbcTypeCode(SqlTypes.VECTOR)` + `@Array(length = 1536)` |
| `SourceType`, `DocumentStatus` | `domain/` | Enums (`PDF/URL/TEXT`, `PENDING/PROCESSING/READY/ERROR`) |
| `DocumentRepository` | `repository/` | `findAllByOrderByCreatedAtDesc()` |
| `ChunkRepository` | `repository/` | Native pgvector `<=>` similarity query + `float[]` overload / literal formatter |

### Ingest layer (`ingest/`)
| Class | Responsibility |
|---|---|
| `ChunkingService` | Fixed-size 500-char windows, 50-char overlap (step 450) |
| `EmbeddingService` | Wraps Spring AI `EmbeddingModel` (single + batch) |
| `IngestService` | Orchestrates extract → chunk → embed → store; drives status lifecycle |
| `IngestException` | Typed ingest failure |

Status writes commit independently of the orchestration body, so a failed embedding/parse still
persists the document as `ERROR` (per spec). Supports text, URL (Jsoup), and PDF (PDFBox `Loader`).

### Query layer (`chat/`)
| Class | Responsibility |
|---|---|
| `LlmClient` | Wraps `ChatModel`; streams assistant tokens as `Flux<String>` |
| `ChatService` | Embed question → top-5 `findNearest` → grounded system prompt → stream |
| `ChatController` | `GET /api/chat/stream?question=...` → SSE (`text/event-stream`) |

### REST API (`document/`, `web/`)
| Method | Path | Handler |
|---|---|---|
| `POST` | `/api/documents/upload` | PDF multipart → `ingestPdf` |
| `POST` | `/api/documents/url` | `ingestUrl` |
| `POST` | `/api/documents/text` | `ingestText` |
| `GET` | `/api/documents` | list, newest first |
| `DELETE` | `/api/documents/{id}` | `204`; `404` if missing; chunks cascade |
| `GET` | `/api/chat/stream` | SSE answer stream |

DTOs (`DocumentResponse`, `UrlIngestRequest`, `TextIngestRequest`) with Bean Validation, plus a
`GlobalExceptionHandler` returning RFC-7807 `ProblemDetail`: **404** not-found, **422** ingest
failure, **400** validation.

---

## Verification

### Automated tests
| Test | Type | Covers |
|---|---|---|
| `ChunkingServiceTest` | unit | Chunk sizing, overlap, edge cases |
| `IngestPipelineStubbedTest` | integration (stubbed AI) | Ingest → store 1536-d vectors → similarity search |
| `ChatPipelineStubbedTest` | integration (stubbed AI) | Retrieval → grounded prompt injection → token streaming |
| `DocumentControllerTest` | `@WebMvcTest` | Routing, validation, error-status mapping |
| `IngestServiceIntegrationTest` | integration (real OpenAI) | Gated by `@EnabledIfEnvironmentVariable("OPENAI_API_KEY")` |

Stubbed integration tests use a deterministic fake `EmbeddingModel`/`ChatModel`, so they run
against real pgvector with **no API key required**.

### Live smoke test (2026-06-27)
Full RAG loop confirmed end-to-end against real pgvector + OpenAI:
- Text and URL ingest stored genuine `vector(1536)` embeddings (`READY`).
- `/api/chat/stream` retrieved the chunk, grounded the LLM, and streamed a correct answer over SSE.
- Validation (`400`), not-found (`404`), and delete-cascade (chunks → 0) all behaved as specified.

---

## Local environment notes
- **pgvector runs on host port `5433`** (a local Postgres already occupies `5432`); the
  `application.yaml` default DB URL points to `5433`.
- Start the database:
  ```bash
  docker run -d --name pgvector \
    -e POSTGRES_DB=knowledgebase -e POSTGRES_USER=kb -e POSTGRES_PASSWORD=kb \
    -p 5433:5432 pgvector/pgvector:pg16
  ```
- Run the backend (key read from a local, gitignored file):
  ```bash
  OPENAI_API_KEY=$(cat .openai_key) ./gradlew bootRun
  ```

---

## Not yet done
- **React frontend** — pages (`LibraryPage`, `UploadPage`, `ChatPage`), components, and hooks
  (`useChat`, `useDocuments`, `useUpload`) per the spec.
- Out of scope for v1 (per spec): auth, multi-tenancy, async ingest, semantic chunking, reranking,
  chat-history persistence.

### Possible backend follow-ups
- Persist a per-chunk source reference for the spec's `SourceCard` (return retrieved sources from chat).
- Tune ivfflat `lists` / add `ANALYZE` guidance as data volume grows.
