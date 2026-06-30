package personal.knowledge.base.chat;

import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

@RestController
@RequestMapping("/api/chat")
@RequiredArgsConstructor
public class ChatController {

    private final ChatService chatService;

    /**
     * Streams the answer to {@code question} as Server-Sent Events, followed by a
     * terminal {@code done} event so the client knows the answer is complete without
     * having to rely on the connection closing (which proxies may delay or buffer).
     */
    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> stream(@RequestParam String question) {
        Flux<ServerSentEvent<String>> tokens =
                chatService.answer(question).map(token -> ServerSentEvent.builder(token).build());
        // The data payload is required: Spring does not serialize a data-less SSE
        // event, so an event with only a name would never reach the client.
        Flux<ServerSentEvent<String>> done =
                Flux.just(ServerSentEvent.builder("[DONE]").event("done").build());
        return Flux.concat(tokens, done);
    }
}
