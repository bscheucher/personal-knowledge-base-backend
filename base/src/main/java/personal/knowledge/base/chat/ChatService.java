package personal.knowledge.base.chat;

import java.util.List;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import personal.knowledge.base.domain.DocumentChunk;
import personal.knowledge.base.ingest.EmbeddingService;
import personal.knowledge.base.repository.ChunkRepository;
import reactor.core.publisher.Flux;

/**
 * Query pipeline: embeds the question, retrieves the most relevant chunks via pgvector, builds a
 * grounded prompt, and streams the LLM's answer.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ChatService {

    private static final int TOP_K = 5;

    private static final String SYSTEM_PROMPT_TEMPLATE =
            """
            You are a knowledge-base assistant. Answer the user's question using ONLY the context \
            below. If the context does not contain the answer, say you don't know rather than \
            guessing.

            Context:
            %s
            """;

    private final EmbeddingService embeddingService;
    private final ChunkRepository chunkRepository;
    private final LlmClient llmClient;

    /** Answers a question, streaming the response token by token. */
    public Flux<String> answer(String question) {
        float[] questionEmbedding = embeddingService.embed(question);
        List<DocumentChunk> chunks = chunkRepository.findNearest(questionEmbedding, TOP_K);

        if (chunks.isEmpty()) {
            log.debug("No chunks available for question: {}", question);
        }

        String context =
                chunks.stream()
                        .map(DocumentChunk::getContent)
                        .collect(Collectors.joining("\n\n---\n\n"));
        String systemPrompt = SYSTEM_PROMPT_TEMPLATE.formatted(context);

        return llmClient.stream(systemPrompt, question);
    }
}
