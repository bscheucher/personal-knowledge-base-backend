package personal.knowledge.base.chat;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

@RestController
@RequestMapping("/api/chat")
@RequiredArgsConstructor
@Slf4j
public class ChatController {
    static final int MAX_QUESTION_LENGTH = 4_000;
    static final String CLIENT_ERROR_MESSAGE = "The answer stream was interrupted";

    private final ChatService chatService;

    /**
     * SSE contract:
     * <ul>
     *   <li>{@code token}: {@code {"text":"..."}}; whitespace is preserved by JSON encoding.</li>
     *   <li>{@code done}: {@code {"status":"complete"}} and no more events follow.</li>
     *   <li>{@code error}: {@code {"status":"interrupted","message":"..."}} and no done event follows.</li>
     * </ul>
     * Failures before a stream can be created are returned as HTTP problem details.
     */
    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<Flux<ServerSentEvent<Object>>> stream(
            @RequestParam @NotBlank @Size(max = MAX_QUESTION_LENGTH) String question) {
        Flux<ServerSentEvent<Object>> tokens =
                chatService
                        .answer(question)
                        .map(
                                token ->
                                        ServerSentEvent.builder((Object) new TokenPayload(token))
                                                .event("token")
                                                .build());
        ServerSentEvent<Object> done =
                ServerSentEvent.builder((Object) new DonePayload("complete"))
                        .event("done")
                        .build();
        Flux<ServerSentEvent<Object>> events =
                tokens.concatWithValues(done)
                        .onErrorResume(
                                failure -> {
                                    log.error("Chat stream failed", failure);
                                    return Flux.just(
                                            ServerSentEvent.builder(
                                                            (Object)
                                                                    new ErrorPayload(
                                                                            "interrupted",
                                                                            CLIENT_ERROR_MESSAGE))
                                                    .event("error")
                                                    .build());
                                })
                        .doOnCancel(() -> log.debug("Chat stream cancelled by client"));

        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_EVENT_STREAM)
                .cacheControl(CacheControl.noStore())
                .header("X-Accel-Buffering", "no")
                .header("X-Content-Type-Options", "nosniff")
                .body(events);
    }

    public record TokenPayload(String text) {}

    public record DonePayload(String status) {}

    public record ErrorPayload(String status, String message) {}
}
