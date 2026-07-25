package personal.knowledge.base.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.ai.retry.TransientAiException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import personal.knowledge.base.domain.Document;
import personal.knowledge.base.domain.DocumentChunk;
import personal.knowledge.base.domain.DocumentStatus;
import personal.knowledge.base.repository.ChunkRepository;
import personal.knowledge.base.repository.DocumentRepository;
import personal.knowledge.base.support.PgVectorContainerTest;

/**
 * Exercises the full background ingest + retrieval pipeline against the real pgvector database
 * using a deterministic stub {@link EmbeddingModel}, so no OpenAI key or network access is
 * required. Retry/dispatch timing is sped up via property overrides so retry tests stay fast.
 */
@SpringBootTest(
        properties = {
            "spring.ai.openai.api-key=test",
            "app.ingest-jobs.retry-initial-delay=200ms",
            "app.ingest-jobs.retry-max-delay=500ms",
            "app.ingest-jobs.dispatch-interval=300ms"
        })
class IngestPipelineStubbedTest extends PgVectorContainerTest {

    @Autowired private IngestJobService ingestJobService;
    @Autowired private ChunkRepository chunkRepository;
    @Autowired private DocumentRepository documentRepository;

    private Document ingested;
    private final List<Document> additionalDocuments = new ArrayList<>();

    @AfterEach
    void cleanup() {
        if (ingested != null) {
            documentRepository.deleteById(ingested.getId());
        }
        additionalDocuments.forEach(document -> documentRepository.deleteById(document.getId()));
    }

    private Document awaitTerminal(UUID documentId) {
        await().atMost(Duration.ofSeconds(10))
                .until(
                        () ->
                                documentRepository
                                        .findById(documentId)
                                        .map(d -> d.getStatus() == DocumentStatus.READY || d.getStatus() == DocumentStatus.ERROR)
                                        .orElse(false));
        return documentRepository.findById(documentId).orElseThrow();
    }

    @Test
    void ingestsChunksAndStores1536DimEmbeddings() {
        String text =
                "Spring AI provides building blocks for retrieval-augmented generation. "
                        .repeat(40);

        Document pending = ingestJobService.submitText("Stubbed overview", text);
        assertThat(pending.getStatus()).isEqualTo(DocumentStatus.PENDING);
        ingested = awaitTerminal(pending.getId());

        assertThat(ingested.getStatus()).isEqualTo(DocumentStatus.READY);

        List<DocumentChunk> chunks =
                chunkRepository.findByDocument_IdOrderByChunkIndex(ingested.getId());
        assertThat(chunks).isNotEmpty();
        assertThat(chunks.get(0).getChunkIndex()).isZero();
        assertThat(chunks.get(0).getContent()).isNotBlank();
        assertThat(chunks.get(0).getEmbedding()).hasSize(1536);
    }

    @Test
    void similaritySearchReturnsTheMatchingChunkFirst() {
        String text =
                "Vector search retrieves the most relevant passages for a question. ".repeat(40);
        Document pending = ingestJobService.submitText("Stubbed retrieval", text);
        ingested = awaitTerminal(pending.getId());

        List<DocumentChunk> chunks =
                chunkRepository.findByDocument_IdOrderByChunkIndex(ingested.getId());
        DocumentChunk target = chunks.get(0);

        // Query with the exact embedding of a stored chunk -> it must rank first (distance ~0).
        float[] query = StubEmbeddingConfig.deterministicVector(target.getContent());
        List<DocumentChunk> nearest = chunkRepository.findNearest(query, 3);

        assertThat(nearest).isNotEmpty();
        assertThat(nearest.get(0).getContent()).isEqualTo(target.getContent());
    }

    @Test
    void retrievalReturnsChunksFromReadyDocumentsOnly() {
        float[] embedding = StubEmbeddingConfig.deterministicVector("same query");
        for (DocumentStatus status : DocumentStatus.values()) {
            Document document =
                    documentRepository.save(
                            Document.builder()
                                    .title(status.name())
                                    .sourceType(personal.knowledge.base.domain.SourceType.TEXT)
                                    .status(status)
                                    .build());
            additionalDocuments.add(document);
            chunkRepository.save(
                    DocumentChunk.builder()
                            .document(document)
                            .chunkIndex(0)
                            .content(status.name())
                            .embedding(embedding)
                            .build());
        }

        List<DocumentChunk> nearest = chunkRepository.findNearest(embedding, 10);

        assertThat(nearest).extracting(DocumentChunk::getContent).contains("READY");
        assertThat(nearest).extracting(DocumentChunk::getContent)
                .doesNotContain("PENDING", "PROCESSING", "ERROR");
    }

    @Test
    void persistsBoundedFailureReasonFromMigration() {
        Document failed =
                documentRepository.saveAndFlush(
                        Document.builder()
                                .title("Failed")
                                .sourceType(personal.knowledge.base.domain.SourceType.TEXT)
                                .status(DocumentStatus.ERROR)
                                .failureReason("Safe failure reason")
                                .build());
        additionalDocuments.add(failed);

        assertThat(documentRepository.findById(failed.getId()).orElseThrow().getFailureReason())
                .isEqualTo("Safe failure reason");
    }

    @Test
    void retriesATransientFailureAndEventuallySucceeds() {
        String text = StubEmbeddingConfig.TRANSIENT_ONCE_MARKER + " retry me please";

        Document pending = ingestJobService.submitText("Retry once", text);
        ingested = awaitTerminal(pending.getId());

        assertThat(ingested.getStatus()).isEqualTo(DocumentStatus.READY);
        assertThat(ingested.getAttemptCount()).isEqualTo(2);
    }

    @Test
    void exhaustsRetriesAndEndsInErrorWithNoChunks() {
        String text = StubEmbeddingConfig.ALWAYS_TRANSIENT_MARKER + " never succeeds";

        Document pending = ingestJobService.submitText("Always fails", text);
        ingested = awaitTerminal(pending.getId());

        assertThat(ingested.getStatus()).isEqualTo(DocumentStatus.ERROR);
        assertThat(ingested.getAttemptCount()).isEqualTo(3);
        assertThat(ingested.getFailureReason()).isNotBlank();
        assertThat(chunkRepository.findByDocument_IdOrderByChunkIndex(ingested.getId())).isEmpty();
    }

    @TestConfiguration
    static class StubEmbeddingConfig {

        static final String TRANSIENT_ONCE_MARKER = "TRANSIENT_FAILURE_ONCE";
        static final String ALWAYS_TRANSIENT_MARKER = "TRANSIENT_FAILURE_ALWAYS";

        private static final ConcurrentHashMap<String, AtomicInteger> callCounts = new ConcurrentHashMap<>();

        @Bean
        @Primary
        EmbeddingModel stubEmbeddingModel() {
            return new StubEmbeddingModel();
        }

        /** Deterministic, content-derived vector so identical text yields identical embeddings. */
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
                for (String input : inputs) {
                    if (input.contains(ALWAYS_TRANSIENT_MARKER)) {
                        throw new TransientAiException("stub: always-transient failure");
                    }
                    if (input.contains(TRANSIENT_ONCE_MARKER)
                            && callCounts.computeIfAbsent(input, k -> new AtomicInteger()).incrementAndGet()
                                    == 1) {
                        throw new TransientAiException("stub: transient failure on first attempt");
                    }
                }
                List<Embedding> embeddings =
                        java.util.stream.IntStream.range(0, inputs.size())
                                .mapToObj(
                                        i ->
                                                new Embedding(
                                                        deterministicVector(inputs.get(i)), i))
                                .toList();
                return new EmbeddingResponse(embeddings);
            }

            @Override
            public float[] embed(org.springframework.ai.document.Document document) {
                return deterministicVector(document.getText());
            }
        }
    }
}
