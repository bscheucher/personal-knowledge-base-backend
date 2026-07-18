package personal.knowledge.base.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.concurrent.atomic.AtomicBoolean;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

@WebMvcTest(ChatController.class)
class ChatControllerTest {
    @Autowired private MockMvc mockMvc;
    @MockitoBean private ChatService chatService;

    @Test
    void emitsNamedJsonTokenEventsAndStructuredDoneEvent() throws Exception {
        given(chatService.answer("question")).willReturn(Flux.just("hello", " world", " ÖIF"));

        MvcResult result =
                mockMvc.perform(get("/api/chat/stream").param("question", "question"))
                        .andExpect(status().isOk())
                        .andExpect(request().asyncStarted())
                        .andExpect(content().contentTypeCompatibleWith("text/event-stream"))
                        .andExpect(header().string("Cache-Control", "no-store"))
                        .andExpect(header().string("X-Accel-Buffering", "no"))
                        .andReturn();

        String body =
                mockMvc.perform(asyncDispatch(result))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString(StandardCharsets.UTF_8);

        assertThat(body)
                .contains("event:token")
                .contains("data:{\"text\":\"hello\"}")
                .contains("data:{\"text\":\" world\"}")
                .contains("data:{\"text\":\" ÖIF\"}")
                .endsWith("event:done\ndata:{\"status\":\"complete\"}\n\n");
    }

    @Test
    void streamFailureEmitsSafeTerminalErrorWithoutDone() throws Exception {
        given(chatService.answer("question"))
                .willReturn(Flux.concat(Flux.just("partial"), Flux.error(new RuntimeException("secret upstream detail"))));

        MvcResult result =
                mockMvc.perform(get("/api/chat/stream").param("question", "question"))
                        .andExpect(request().asyncStarted())
                        .andReturn();
        String body =
                mockMvc.perform(asyncDispatch(result))
                        .andReturn()
                        .getResponse()
                        .getContentAsString(StandardCharsets.UTF_8);

        assertThat(body)
                .contains("event:token", "data:{\"text\":\"partial\"}", "event:error")
                .contains("data:{\"status\":\"interrupted\",\"message\":\"The answer stream was interrupted\"}")
                .doesNotContain("event:done", "secret upstream detail");
    }

    @Test
    void synchronousFailureReturnsSafeProblemDetail() throws Exception {
        given(chatService.answer(anyString()))
                .willThrow(new ChatException("The answer could not be started", new RuntimeException("secret")));

        mockMvc.perform(get("/api/chat/stream").param("question", "question"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.detail").value("The answer could not be started"));
    }

    @Test
    void rejectsBlankQuestion() throws Exception {
        mockMvc.perform(get("/api/chat/stream").param("question", "   "))
                .andExpect(status().isBadRequest());
    }

    @Test
    void rejectsQuestionOverLengthLimit() throws Exception {
        mockMvc.perform(
                        get("/api/chat/stream")
                                .param("question", "x".repeat(ChatController.MAX_QUESTION_LENGTH + 1)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void clientCancellationPropagatesToAnswerPublisherAndDoesNotCreateDone() {
        AtomicBoolean cancelled = new AtomicBoolean();
        given(chatService.answer("question")).willReturn(Flux.<String>never().doOnCancel(() -> cancelled.set(true)));
        Flux<?> events = new ChatController(chatService).stream("question").getBody();

        StepVerifier.create(events).thenCancel().verify();

        assertThat(cancelled).isTrue();
    }
}
