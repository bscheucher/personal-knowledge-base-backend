# Recommended implementation plan

This plan turns the current personal knowledge-base MVP into a safer, more reliable application.
It covers both repositories:

- Backend: `~/projects/ai/base_backend/base`
- Frontend: `~/projects/ai/base_frontend`

The phases are ordered by risk and dependency. Complete each phase, including its tests and
acceptance criteria, before starting work that depends on it.

## Guiding principles

- Preserve the current, simple architecture until scale requires another component.
- Treat uploaded documents and fetched web pages as untrusted input.
- Keep document lifecycle state consistent with the chunks that are searchable.
- Prefer deterministic tests with stubbed AI models; reserve live OpenAI tests for an optional
  integration suite.
- Introduce operational complexity only after establishing security, correctness, and repeatable
  tests.

## Phase 1: Secure URL ingestion

### Goal

Prevent URL ingestion from reaching private infrastructure or consuming unbounded resources.

### Backend work

1. Introduce a dedicated URL-fetching service rather than calling `Jsoup.connect()` from
   `IngestService`.
2. Parse and normalize the submitted URI. Accept only `http` and `https`; reject user-info,
   malformed hosts, and unsupported schemes.
3. Resolve the hostname and reject every loopback, private, link-local, multicast, unspecified,
   and reserved address. Apply the same validation after every redirect.
4. Limit redirect count and reject protocol changes to unsupported schemes.
5. Configure connection and read timeouts, a maximum response size, and an explicit user agent.
6. Accept only suitable textual content types. Do not attempt to ingest arbitrary binary bodies.
7. Return stable, user-safe error details without exposing internal addresses or stack traces.
8. Move limits and timeouts into typed configuration properties so deployments can tune them.

### Tests

- Unit-test URI validation for public HTTP(S), malformed URLs, unsupported schemes, localhost,
  IPv4 and IPv6 private ranges, encoded addresses, and DNS results containing mixed public/private
  addresses.
- Test redirects to private addresses, redirect loops, timeouts, oversized responses, unsupported
  content types, and missing bodies using a local mock HTTP server.
- Verify that URL ingestion still extracts and ingests a normal public HTML page.

### Acceptance criteria

- No request can reach a loopback, private, link-local, or cloud metadata address.
- Redirect destinations receive the same validation as the original URL.
- Fetches have deterministic time and size bounds.
- Existing text and PDF ingestion behavior is unchanged.

## Phase 2: Make ingestion and retrieval consistent

### Goal

Ensure that only fully ingested, `READY` documents can influence chat answers.

### Backend work

1. Define explicit transaction boundaries for document lifecycle updates. Avoid relying on the
   implicit transactions of individual repository calls.
2. Keep the initial `PENDING` creation and failure-state update in small independent transactions.
3. Commit chunk insertion and the transition to `READY` atomically.
4. On failure, ensure partial chunks are removed before persisting `ERROR`.
5. Update nearest-neighbor retrieval to join `document` and require `status = 'READY'`.
6. Validate that the embedding service returns exactly one correctly sized vector per chunk before
   writing anything.
7. Store a bounded failure reason on the document in a new Flyway migration so the API and UI can
   explain failed ingestion.
8. Define recovery for documents left in `PENDING` or `PROCESSING` by a process crash. Until
   background jobs are introduced, startup recovery may mark stale records as `ERROR`.

### Frontend work

1. Extend `DocumentResponse` and the document list to show a safe failure reason for `ERROR`
   documents.
2. Preserve the current status badges and ensure an error does not appear as a successful upload.

### Tests

- Force failures during extraction, embedding, chunk persistence, and final document update.
- Assert that failed documents have no searchable chunks.
- Assert that retrieval excludes `PENDING`, `PROCESSING`, and `ERROR` documents even if test data
  inserts chunks for them directly.
- Test embedding count and dimension mismatches.
- Add migration coverage for the failure-reason column.

### Acceptance criteria

- A document and its chunks cannot expose a half-completed successful state.
- Only chunks belonging to `READY` documents are retrieved.
- Every ingest failure leaves a stable `ERROR` record with no partial searchable data.

## Phase 3: Harden and test chat streaming

### Goal

Make the SSE contract reliable across browsers, proxies, line endings, and partial failures.

### Backend work

1. Document the SSE event contract: token events, terminal `done` event, and error behavior.
2. Give token events an explicit event name instead of relying on unnamed data frames.
3. Emit a structured terminal payload that can later carry answer metadata and citations.
4. Validate that questions are non-blank and impose a reasonable length limit.
5. Define behavior for failures before the first token and after a partial answer. Log internal
   details while returning a safe client-facing error.
6. Add appropriate anti-buffering/cache headers where supported by the deployment stack.

### Frontend work

1. Extract SSE decoding from `useChat` into a small independently tested parser.
2. Support both LF and CRLF framing, chunk boundaries inside UTF-8 characters, multiline `data`
   fields, comments, and a final buffered frame.
3. Flush `TextDecoder` when the response body closes.
4. Display a clear interrupted-answer state when a stream fails after tokens have arrived.
5. Parse backend problem details for failures that occur before streaming begins.
6. Preserve chat history when navigating between pages, at least for the current browser session.

### Tests

- Add backend controller tests that assert media type, token frames, the terminal event, validation,
  cancellation, and upstream model failures.
- Add frontend unit tests for fragmented frames, CRLF, split Unicode, leading token spaces,
  multiline data, terminal events, aborts, and partial-stream errors.
- Add one integration test that streams a stubbed answer through the backend HTTP endpoint and the
  frontend parser.

### Acceptance criteria

- The UI always leaves its streaming state after completion, cancellation, or failure.
- Leading spaces and Unicode are preserved exactly.
- Partial failures are visible and distinguishable from successful completion.
- The stream contract is covered by tests on both sides of the API boundary.

## Phase 4: Establish reproducible tests and CI

### Goal

Make every change verifiable without a manually prepared local database or a live OpenAI account.

### Backend work

1. Add Testcontainers for PostgreSQL with pgvector and configure integration tests dynamically.
2. Separate fast unit/web tests, container-backed integration tests, and optional live-OpenAI tests.
3. Ensure tests create and clean up their own data and can run repeatedly or in parallel.
4. Run Flyway migrations as part of container-backed tests.

### Frontend work

1. Add Vitest, React Testing Library, and a DOM test environment.
2. Test API error handling, upload behavior, document deletion errors, and chat streaming.
3. Add a production build check in addition to TypeScript checking.

### Repository and CI work

1. Add a CI workflow that runs backend unit and container integration tests, frontend tests,
   TypeScript checks, and the frontend production build.
2. Cache Gradle and npm dependencies without caching build outputs.
3. Upload test reports on failure and cancel obsolete runs for the same branch.
4. Add Dependabot or an equivalent dependency-update workflow after CI is stable.

### Acceptance criteria

- A clean CI runner can validate both repositories with no pre-existing database.
- The default test suite makes no live OpenAI calls.
- A failing migration, API contract, TypeScript check, or production build blocks merging.

## Phase 5: Move ingestion to background jobs

### Goal

Return quickly from uploads and make long-running ingestion observable and recoverable.

### Backend work

1. Change ingest endpoints to create a `PENDING` document and return `202 Accepted` with its ID.
2. Process ingestion through a bounded executor initially. Do not add a separate queue until
   durability or multi-instance processing requires one.
3. Persist job attempts, timestamps, progress, and failure reasons so state survives restarts.
4. Make processing idempotent and prevent two workers from ingesting the same document.
5. Add retry policy only for transient failures, with bounded exponential backoff.
6. Support cancellation/deletion without allowing a worker to recreate deleted chunks.
7. Add a document-detail/status endpoint or extend the list endpoint with sufficient polling data.
8. Add cleanup rules for abandoned jobs and temporary upload data.

### Frontend work

1. Treat a successful upload as accepted rather than complete.
2. Poll active documents with React Query and stop polling when they reach `READY` or `ERROR`.
3. Show meaningful pending, processing, retrying, ready, and error states.
4. Prevent duplicate submissions while preserving navigation during processing.

### Tests

- Test state transitions, retries, restart recovery, duplicate delivery, cancellation, deletion
  races, and executor saturation.
- Verify that API responses remain fast while embeddings are deliberately delayed.
- Verify frontend polling starts and stops at the correct states.

### Acceptance criteria

- Upload endpoints do not wait for extraction or OpenAI calls.
- In-progress work is bounded, observable, idempotent, and recoverable.
- A restart cannot silently strand a document forever in `PROCESSING`.

## Phase 6: Improve retrieval quality and add citations

### Goal

Reduce irrelevant answers and let users see which documents support a response.

### Backend work

1. Return similarity distance, document ID, title, source type, and chunk index from retrieval.
2. Add a configurable maximum distance or minimum relevance threshold. If nothing qualifies, avoid
   calling the chat model and return an explicit no-context response.
3. Preserve source boundaries when constructing the prompt and assign stable citation identifiers.
4. Include citations in the terminal SSE payload, separate from generated answer text.
5. Add a maximum context budget and deterministic selection rules rather than relying only on
   `TOP_K`.
6. Evaluate sentence-aware or token-aware chunking before adopting it. Keep chunking behind the
   existing service boundary.
7. Build a small checked-in evaluation set of questions, expected source documents, and known
   no-answer cases.

### Frontend work

1. Store citations with each assistant message.
2. Render cited document titles and, where possible, a short supporting excerpt.
3. Let users navigate from a citation to document details.
4. Clearly distinguish “no relevant context” from infrastructure failure.

### Tests and evaluation

- Test threshold boundaries, deterministic ordering, context budget enforcement, and citation
  mapping.
- Track retrieval hit rate and no-answer correctness against the evaluation set.
- Compare chunking or retrieval changes using the same evaluation set before merging them.

### Acceptance criteria

- Unrelated questions do not force the model to answer from weakly matching chunks.
- Every grounded answer can identify its source documents.
- Retrieval changes have a repeatable quality measurement rather than anecdotal verification.

## Phase 7: Add authentication and document ownership

### Goal

Make remote deployment safe by isolating each user's documents and chat context.

This phase can move earlier if the application will be exposed beyond a trusted local environment.

### Backend work

1. Choose an identity provider and use standard OIDC/OAuth2 integration rather than building
   password storage locally.
2. Add an immutable owner identifier to documents in a Flyway migration and define a safe migration
   path for existing rows.
3. Scope list, delete, status, and retrieval queries by the authenticated owner.
4. Never fetch a document globally and check ownership afterward when a scoped query can enforce it.
5. Return `404` for resources not owned by the caller to avoid identifier disclosure.
6. Configure CSRF and CORS according to the final deployment topology.
7. Add upload, ingest, chat, and request-rate limits per user.
8. Define document retention and account-deletion behavior.

### Frontend work

1. Add login, logout, session restoration, and expired-session handling.
2. Route unauthenticated users to login and retry safe queries after session restoration.
3. Avoid storing access tokens in local storage when a secure HTTP-only session is available.

### Tests

- Test every endpoint with no identity, the owner, and a different user.
- Prove that cross-user documents never appear in retrieval results.
- Test expired sessions, logout, CSRF protection, rate limits, and ownership-preserving deletion.

### Acceptance criteria

- No user can list, retrieve, chat with, modify, or delete another user's content.
- Authentication and authorization are enforced by the API, not only by frontend routing.
- Remote deployment has documented session, CORS, CSRF, and retention settings.

## Cross-cutting operational work

Add these incrementally alongside the phases that need them:

- Provide Docker Compose for local PostgreSQL/pgvector and documented startup commands.
- Add Spring Boot health/readiness endpoints without exposing sensitive configuration.
- Add structured request IDs and propagate them through ingest jobs and logs.
- Record metrics for fetch, extraction, embedding, retrieval, model latency, stream completion, and
  failure rates.
- Configure explicit timeouts for every external call.
- Keep secrets in environment-specific secret storage and ensure logs never contain credentials or
  full sensitive document contents.
- Add production profiles for database pools, proxy behavior, logging, and schema migration policy.

## Suggested delivery milestones

1. **Security baseline:** Phases 1 and 2.
2. **Reliable developer loop:** Phases 3 and 4.
3. **Operational MVP:** Phase 5 plus health checks, metrics, and Docker Compose.
4. **Useful RAG experience:** Phase 6.
5. **Remote/multi-user release:** Phase 7, completed before any untrusted-user deployment.

Each milestone should end with updated API documentation, migration notes, and a short manual smoke
test covering upload, document status, retrieval, chat completion, failure behavior, and deletion.
