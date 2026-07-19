package personal.knowledge.base.chat;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import personal.knowledge.base.domain.Document;
import personal.knowledge.base.ingest.IngestService;
import personal.knowledge.base.repository.DocumentRepository;
import personal.knowledge.base.support.PgVectorContainerTest;
import reactor.core.publisher.Flux;

/**
 * Verifies the query pipeline (embed question → retrieve chunks → build grounded prompt → stream)
 * end-to-end against real pgvector, with the embedding and chat models stubbed so no OpenAI key is
 * required.
 */
@SpringBootTest(properties = "spring.ai.openai.api-key=test")
class ChatPipelineStubbedTest extends PgVectorContainerTest {

    @Autowired private IngestService ingestService;
    @Autowired private ChatService chatService;
    @Autowired private DocumentRepository documentRepository;
    @Autowired private RecordingChatModel chatModel;

    private Document ingested;

    @AfterEach
    void cleanup() {
        if (ingested != null) {
            documentRepository.deleteById(ingested.getId());
        }
    }

    @Test
    void retrievesContextBuildsGroundedPromptAndStreamsAnswer() {
        ingested =
                ingestService.ingestText(
                        "Geography", "The capital of France is Paris. ".repeat(40));

        List<String> tokens =
                chatService.answer("What is the capital of France?").collectList().block();

        // Streamed tokens are surfaced in order.
        assertThat(String.join("", tokens)).isEqualTo("The capital is Paris.");

        // The retrieved chunk content was injected into the system prompt.
        String systemText =
                chatModel.lastPrompt.getInstructions().stream()
                        .filter(m -> m instanceof SystemMessage)
                        .map(Message::getText)
                        .findFirst()
                        .orElseThrow();
        assertThat(systemText).contains("capital of France is Paris");
    }

    @TestConfiguration
    static class StubConfig {

        @Bean
        @Primary
        EmbeddingModel stubEmbeddingModel() {
            return new StubEmbeddingModel();
        }

        @Bean
        @Primary
        RecordingChatModel recordingChatModel() {
            return new RecordingChatModel();
        }
    }

    static float[] deterministicVector(String text) {
        float[] v = new float[1536];
        Random random = new Random(text.hashCode());
        for (int i = 0; i < v.length; i++) {
            v[i] = random.nextFloat();
        }
        return v;
    }

    static class StubEmbeddingModel implements EmbeddingModel {
        @Override
        public EmbeddingResponse call(EmbeddingRequest request) {
            List<String> inputs = request.getInstructions();
            List<Embedding> embeddings =
                    java.util.stream.IntStream.range(0, inputs.size())
                            .mapToObj(i -> new Embedding(deterministicVector(inputs.get(i)), i))
                            .toList();
            return new EmbeddingResponse(embeddings);
        }

        @Override
        public float[] embed(org.springframework.ai.document.Document document) {
            return deterministicVector(document.getText());
        }
    }

    /** Records the last prompt it received and streams a fixed set of tokens. */
    static class RecordingChatModel implements ChatModel {

        volatile Prompt lastPrompt;

        @Override
        public ChatResponse call(Prompt prompt) {
            this.lastPrompt = prompt;
            return new ChatResponse(
                    List.of(new Generation(new org.springframework.ai.chat.messages.AssistantMessage(
                            "The capital is Paris."))));
        }

        @Override
        public Flux<ChatResponse> stream(Prompt prompt) {
            this.lastPrompt = prompt;
            return Flux.just("The capital ", "is Paris.")
                    .map(
                            token ->
                                    new ChatResponse(
                                            List.of(
                                                    new Generation(
                                                            new org.springframework.ai.chat.messages
                                                                    .AssistantMessage(token)))));
        }
    }
}
