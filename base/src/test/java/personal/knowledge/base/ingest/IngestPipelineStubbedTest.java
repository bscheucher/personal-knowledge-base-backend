package personal.knowledge.base.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Random;
import java.util.ArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
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
import personal.knowledge.base.domain.DocumentChunk;
import personal.knowledge.base.domain.DocumentStatus;
import personal.knowledge.base.repository.ChunkRepository;
import personal.knowledge.base.repository.DocumentRepository;

/**
 * Exercises the full ingest + retrieval pipeline against the real pgvector database using a
 * deterministic stub {@link EmbeddingModel}, so no OpenAI key or network access is required.
 */
@SpringBootTest(properties = "spring.ai.openai.api-key=test")
class IngestPipelineStubbedTest {

    @Autowired private IngestService ingestService;
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

    @Test
    void ingestsChunksAndStores1536DimEmbeddings() {
        String text =
                "Spring AI provides building blocks for retrieval-augmented generation. "
                        .repeat(40);

        ingested = ingestService.ingestText("Stubbed overview", text);

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
        ingested = ingestService.ingestText("Stubbed retrieval", text);

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

    @TestConfiguration
    static class StubEmbeddingConfig {

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
