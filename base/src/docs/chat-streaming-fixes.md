# Chat streaming — debugging findings & fixes

_Investigated 2026-06-30. Covers the SSE chat pipeline spanning the backend
(`base_backend`) and the React frontend (`base_frontend`)._

While bringing up the chat UI, three defects surfaced in sequence. They are
documented here because each was non-obvious and the symptoms pointed at the
wrong layer (the UI looked broken; the actual causes were in the backend stream).

---

## Symptom timeline

1. Assistant answers rendered as one run-on string with **no spaces between
   words** ("TheÖIFPrüfungsordnungpertainsto…"), overflowing horizontally.
2. After fixing the spacing, a **follow-up question was impossible** — the Send
   button stayed disabled after the first answer.

---

## Bug 1 — SSE strips leading spaces from tokens

**Where:** frontend `useChat.ts` (originally used `EventSource`).

The backend streams the model's raw tokens as the SSE `data:` payload, and those
tokens carry the leading space between words (verified on the wire):

```
data:The
data: ÖIF          <- token is " ÖIF", with a leading space
data: Prüf
data: regulations
```

The [SSE spec](https://html.spec.whatwg.org/multipage/server-sent-events.html)
says a client must strip **one** leading space after `data:`. `EventSource` does
this unconditionally, so every word-leading space was eaten, collapsing the text.
Because the result was one space-less "word", it could not wrap and overflowed.

**Fix:** read the stream with `fetch` + a manual SSE frame parser that keeps the
payload after `data:` verbatim (`line.slice(5)`, no space stripping). Added
`break-words` on the message bubble as wrapping defense-in-depth.

> Note: this is fundamentally a backend protocol smell — emitting tokens with
> semantic whitespace directly as `data:` is fragile. A cleaner long-term design
> is to JSON-encode each token (`data:{"t":" regulations"}`), which also lets the
> client keep using `EventSource`.

---

## Bug 2 — the token stream crashed on the final chunk (NPE)

**Where:** backend `LlmClient.stream`.

The original chain was:

```java
chatModel.stream(prompt)
    .map(ChatResponse::getResult)
    .filter(Objects::nonNull)        // never reached for a null result
    .map(Generation::getOutput)
    .map(AbstractMessage::getText)
    .filter(text -> text != null && !text.isEmpty());
```

Reactor's `map` **throws `NullPointerException` if the mapper returns `null`** —
the value is rejected *before* the downstream `filter` runs. OpenAI's stream ends
with a finish chunk whose `getResult()` / `getText()` is `null`, so every answer
terminated with:

```
NullPointerException: The mapper [LlmClient$$Lambda…] returned a null value
    at reactor.core.publisher.FluxOnErrorResume$ResumeSubscriber.onNext
```

The visible answer text mostly arrived, but the `Flux` ended in **error**, not
completion. This also silently defeated the Bug 3 fix below (an error short-
circuits `Flux.concat`, so a trailing event never emits).

**Fix:** use `mapNotNull`, which is designed to drop `null` elements instead of
throwing:

```java
chatModel.stream(prompt)
    .mapNotNull(ChatResponse::getResult)
    .mapNotNull(Generation::getOutput)
    .mapNotNull(AbstractMessage::getText)
    .filter(text -> !text.isEmpty());
```

---

## Bug 3 — no completion signal, so the client never knew the answer ended

**Where:** backend `ChatController` + frontend `useChat.ts`.

The stream had no explicit end marker; the frontend inferred completion from the
connection closing. That is unreliable:

- The **Vite dev proxy holds the connection open** — a direct request to `:8080`
  closed in ~1.3s, but the same request through the proxy on `:5173` stayed open
  until the client's 25s timeout.
- Real-world reverse proxies also buffer/hold SSE connections.

With the read loop waiting forever on a connection that never closed,
`isStreaming` never reset and the Send button stayed disabled → no follow-up.

**Fix:** emit an explicit terminal event (the same pattern OpenAI's API uses with
`data: [DONE]`) and stop the client on it instead of waiting for the socket:

```java
Flux<ServerSentEvent<String>> tokens =
        chatService.answer(question).map(t -> ServerSentEvent.builder(t).build());
// A data payload is REQUIRED: Spring does not serialize a data-less SSE event,
// so an event with only a name would never reach the client.
Flux<ServerSentEvent<String>> done =
        Flux.just(ServerSentEvent.builder("[DONE]").event("done").build());
return Flux.concat(tokens, done);
```

The frontend stops the read loop when it sees the `event: done` (or `[DONE]`
data) frame, then aborts the still-open connection so cleanup runs and
`isStreaming` resets.

> Gotcha discovered here: a `ServerSentEvent` built with only `.event("done")`
> and no data is **dropped** by Spring's SSE writer — the marker must carry a
> data payload to make it onto the wire.

---

## Verification

Run against a fresh build of the backend:

- Tokens stream with correct spacing.
- A trailing `event:done` / `data:[DONE]` frame is emitted.
- The request returns in ~2.6s instead of hanging for 25s.
- No `NullPointerException` in the logs.
- The frontend parser detects the sentinel and exits ~2ms after the last token,
  resetting `isStreaming` so follow-up questions work.

---

## Files changed

| Repo | File | Change |
|---|---|---|
| `base_backend` | `chat/LlmClient.java` | `map` → `mapNotNull` (fix end-of-stream NPE) |
| `base_backend` | `chat/ChatController.java` | append terminal `event: done` / `[DONE]` frame |
| `base_frontend` | `hooks/useChat.ts` | `fetch` + manual SSE parser; preserve spaces; stop on `done` |
| `base_frontend` | `components/ChatMessage.tsx` | `break-words` for long-token wrapping |

## Possible follow-ups

- JSON-encode streamed tokens on the backend to remove the SSE whitespace
  fragility entirely (and allow `EventSource` again).
- Surface retrieved source chunks from `/api/chat/stream` so the spec's
  `SourceCard` can be implemented.
