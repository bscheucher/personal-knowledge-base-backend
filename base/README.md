# Knowledge base — backend

A personal RAG (Retrieval-Augmented Generation) knowledge base: upload documents, and chat with
them grounded in your own content. Documents are chunked, embedded with OpenAI, and stored as
vectors in PostgreSQL (pgvector); questions retrieve the most relevant chunks to ground the LLM's
streamed answer.

- **Stack:** Spring Boot 3.5, Java 21, Spring AI 1.1, PostgreSQL 16 + pgvector, Flyway, Gradle (Kotlin DSL)
- **Spec:** [`src/docs/knowledge-base-spec.md`](src/docs/knowledge-base-spec.md)
- **Progress:** [`src/docs/implementation-progress.md`](src/docs/implementation-progress.md)

---

## Prerequisites

- Java 21
- Docker (for PostgreSQL + pgvector)
- An OpenAI API key (for embeddings + chat)

---

## Quickstart

### 1. Start PostgreSQL with pgvector

```bash
docker run -d --name pgvector \
  -e POSTGRES_DB=knowledgebase \
  -e POSTGRES_USER=kb \
  -e POSTGRES_PASSWORD=kb \
  -p 5433:5432 \
  pgvector/pgvector:pg16
```

> **Port 5433:** this project defaults to host port **5433** to avoid clashing with a local
> Postgres on 5432. To use a different host/port, override `DB_URL` (see [Configuration](#configuration)).

### 2. Provide your OpenAI API key

Store the key in a local file that is **not** committed (e.g. `.openai_key`):

```bash
umask 077 && printf '%s' 'sk-your-key-here' > .openai_key
```

### 3. Run the backend

```bash
OPENAI_API_KEY=$(cat .openai_key) ./gradlew bootRun
```

On startup, Flyway applies the schema (`document`, `document_chunk`, the pgvector extension, and the
ivfflat index). The API is then available at `http://localhost:8080`.

---

## Configuration

Settings live in [`src/main/resources/application.yaml`](src/main/resources/application.yaml) and
read from environment variables (with local-dev defaults):

| Variable | Default | Description |
|---|---|---|
| `DB_URL` | `jdbc:postgresql://localhost:5433/knowledgebase` | JDBC URL |
| `DB_USER` | `kb` | Database user |
| `DB_PASSWORD` | `kb` | Database password |
| `OPENAI_API_KEY` | _(required)_ | OpenAI API key |

Models: `text-embedding-3-small` (1536 dims) for embeddings, `gpt-4o-mini` for chat.

---

## API

Base URL: `http://localhost:8080`

| Method | Path | Description |
|---|---|---|
| `POST` | `/api/documents/upload` | Upload a PDF (`multipart/form-data`, field `file`) |
| `POST` | `/api/documents/url` | Ingest a URL — `{"url": "..."}` |
| `POST` | `/api/documents/text` | Ingest raw text — `{"title": "...", "text": "..."}` |
| `GET` | `/api/documents` | List all documents (newest first) |
| `DELETE` | `/api/documents/{id}` | Delete a document and its chunks |
| `GET` | `/api/chat/stream?question=...` | Stream a grounded answer via Server-Sent Events |

### Examples

```bash
# Ingest raw text
curl -X POST http://localhost:8080/api/documents/text \
  -H 'Content-Type: application/json' \
  -d '{"title":"Notes","text":"Retrieval-augmented generation grounds answers in your documents."}'

# Ingest a URL
curl -X POST http://localhost:8080/api/documents/url \
  -H 'Content-Type: application/json' \
  -d '{"url":"https://example.com"}'

# Upload a PDF
curl -X POST http://localhost:8080/api/documents/upload \
  -F 'file=@/path/to/document.pdf'

# List documents
curl http://localhost:8080/api/documents

# Chat (Server-Sent Events)
curl --no-buffer -G http://localhost:8080/api/chat/stream \
  --data-urlencode 'question=What is retrieval-augmented generation?'
```

---

## Testing

```bash
# Fast unit and web tests; no Docker, database, or API key required
./gradlew test

# Integration tests; Testcontainers starts PostgreSQL/pgvector and Flyway runs automatically
./gradlew integrationTest

# Both default suites
./gradlew check

# Run only the OpenAI-backed end-to-end test (needs a funded key)
OPENAI_API_KEY=$(cat .openai_key) ./gradlew liveOpenAiTest
```

The container-backed integration tests use deterministic fake AI models, create isolated test
data, and exercise the full ingest/retrieval pipeline without an API key or manually prepared
database. The live suite is separate, gated by `OPENAI_API_KEY`, and never runs as part of `check`.

---

## Project layout

```
src/main/java/personal/knowledge/base/
├── domain/      # JPA entities + enums (Document, DocumentChunk, ...)
├── repository/  # Spring Data repositories (incl. native pgvector query)
├── ingest/      # ChunkingService, EmbeddingService, IngestService
├── chat/        # LlmClient, ChatService, ChatController (SSE)
├── document/    # DocumentController + request/response DTOs
└── web/         # GlobalExceptionHandler
src/main/resources/
├── application.yaml
└── db/migration/V1__init.sql
```
