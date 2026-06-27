# Knowledge base — project specification

## Overview

A personal RAG (Retrieval-Augmented Generation) knowledge base that lets users upload documents and chat with them using an LLM. The system ingests documents, splits them into chunks, stores vector embeddings in PostgreSQL via pgvector, and retrieves relevant passages at query time to ground LLM answers in the user's own content.

---

## Tech stack

| Layer | Technology |
|---|---|
| Backend | Spring Boot 3.5.x, Java 21 |
| Frontend | React 19, TypeScript |
| Database | PostgreSQL 16 + pgvector extension |
| AI | Spring AI 1.0.0 — OpenAI (embeddings + chat) |
| Migrations | Flyway |
| Build | Gradle with Kotlin DSL |

---

## Architecture

Two distinct pipelines share the same database:

### Ingest pipeline
1. User uploads a file (PDF) or provides a URL or raw text
2. Backend extracts plain text (PDFBox for PDF, Jsoup for URLs)
3. Text is split into overlapping chunks (~500 chars, 50-char overlap)
4. Each chunk is embedded via the OpenAI embeddings API
5. Chunks and their vectors are persisted to `document_chunk`

### Query pipeline
1. User sends a question via the React chat UI
2. Backend embeds the question using the same embedding model
3. pgvector cosine similarity search retrieves the top-5 most relevant chunks
4. Retrieved chunks are injected into a prompt as context
5. LLM generates an answer, streamed back to the UI via SSE

---

## Data model

### `document`

| Column | Type | Notes |
|---|---|---|
| `id` | UUID | Primary key |
| `title` | TEXT | File name or URL |
| `source_type` | TEXT | `PDF`, `URL`, `TEXT` |
| `status` | TEXT | `PENDING`, `PROCESSING`, `READY`, `ERROR` |
| `created_at` | TIMESTAMPTZ | Set on insert |

### `document_chunk`

| Column | Type | Notes |
|---|---|---|
| `id` | UUID | Primary key |
| `document_id` | UUID | FK → `document`, cascade delete |
| `chunk_index` | INT | Position within the document |
| `content` | TEXT | Raw chunk text |
| `embedding` | vector(1536) | OpenAI `text-embedding-3-small` output |
| `created_at` | TIMESTAMPTZ | Set on insert |

Index: `USING ivfflat (embedding vector_cosine_ops)` on `document_chunk`.

---

## REST API

### Document endpoints

| Method | Path | Description |
|---|---|---|
| `POST` | `/api/documents/upload` | Upload a PDF (`multipart/form-data`) |
| `POST` | `/api/documents/url` | Ingest a URL (`{ "url": "..." }`) |
| `POST` | `/api/documents/text` | Ingest raw text |
| `GET` | `/api/documents` | List all documents with status |
| `DELETE` | `/api/documents/{id}` | Delete document and its chunks |

### Chat endpoint

| Method | Path | Description |
|---|---|---|
| `GET` | `/api/chat/stream` | SSE stream — `?question=...` |

---

## Backend components

| Class | Responsibility |
|---|---|
| `DocumentController` | Handles upload, URL, and text ingest requests |
| `ChatController` | SSE streaming endpoint |
| `IngestService` | Orchestrates text extraction → chunking → embedding → storage |
| `ChunkingService` | Fixed-size chunking with overlap |
| `EmbeddingService` | Wraps Spring AI `EmbeddingModel` |
| `ChatService` | Embeds question, retrieves chunks, builds prompt, streams response |
| `DocumentRepository` | JPA repository for `document` |
| `ChunkRepository` | JPA repository + native pgvector similarity query |
| `LlmClient` | Wraps Spring AI `ChatModel` |

---

## Frontend pages and components

### Pages

| Page | Path | Description |
|---|---|---|
| `LibraryPage` | `/` | List of all uploaded documents with status badges |
| `UploadPage` | `/upload` | Drag-and-drop upload form with ingest progress |
| `ChatPage` | `/chat` | Chat interface with SSE streaming |

### Key components

| Component | Description |
|---|---|
| `DocumentList` | Table of documents, status badge, delete button |
| `UploadForm` | Accepts PDF, URL input, or pasted text; shows progress |
| `ChatWindow` | Scrollable message history |
| `ChatMessage` | Renders a single user or assistant message |
| `SourceCard` | Shows which document chunks the answer was drawn from |

### Key hooks

| Hook | Description |
|---|---|
| `useChat` | Opens `EventSource`, appends streamed tokens to state |
| `useDocuments` | TanStack Query — fetches and caches document list |
| `useUpload` | Mutation hook for file and URL ingest with status polling |

---

## Configuration (`application.yml`)

```yaml
spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/knowledgebase
    username: ${DB_USER}
    password: ${DB_PASSWORD}
  jpa:
    hibernate:
      ddl-auto: validate
  flyway:
    enabled: true
  ai:
    openai:
      api-key: ${OPENAI_API_KEY}
      embedding:
        options:
          model: text-embedding-3-small
      chat:
        options:
          model: gpt-4o-mini
    vectorstore:
      pgvector:
        dimensions: 1536
        distance-type: COSINE_DISTANCE
        initialize-schema: false
```

---

## Chunking strategy

- **Chunk size:** 500 characters
- **Overlap:** 50 characters (so context is not lost at chunk boundaries)
- **Strategy:** Fixed-size (naive) — sufficient for a first version; can be upgraded to sentence-aware splitting later

---

## Ingest status flow

```
PENDING → PROCESSING → READY
                    ↘ ERROR
```

Status is updated synchronously within `IngestService`. A failed embedding call or parse error transitions the document to `ERROR` with the cause logged.

---

## Local development setup

### Prerequisites
- Docker (for Postgres + pgvector)
- Java 21
- Node.js 20+

### Start Postgres with pgvector

```bash
docker run -d \
  --name pgvector \
  -e POSTGRES_DB=knowledgebase \
  -e POSTGRES_USER=kb \
  -e POSTGRES_PASSWORD=kb \
  -p 5432:5432 \
  pgvector/pgvector:pg16
```

### Run the backend

```bash
./gradlew bootRun
```

### Run the frontend

```bash
npm install
npm run dev
```

---

## Build order

1. Stand up Postgres with pgvector and verify the `vector` extension is available
2. Write and run the Flyway migration — confirm tables and index exist
3. Implement `IngestService` with hardcoded test text (no file upload yet)
4. Verify embeddings are stored by querying `document_chunk` in psql
5. Implement `ChatService` and test via `curl`
6. Add `ChatController` SSE endpoint, test with `curl --no-buffer`
7. Build React `useChat` hook and minimal chat UI
8. Add `UploadPage` with PDF and URL support last

---

## Out of scope (v1)

- User authentication
- Multi-tenancy (documents are shared across all users)
- Async background processing (ingest is synchronous in v1)
- Sentence-aware or semantic chunking
- Reranking retrieved chunks before prompting
- Chat history persistence
