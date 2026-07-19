package personal.knowledge.base.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import personal.knowledge.base.domain.Document;
import personal.knowledge.base.domain.DocumentChunk;
import personal.knowledge.base.domain.DocumentStatus;
import personal.knowledge.base.repository.ChunkRepository;
import personal.knowledge.base.repository.DocumentRepository;
import personal.knowledge.base.support.PgVectorContainerTest;

/**
 * End-to-end ingest against the real pgvector database and the OpenAI embeddings API.
 * Skipped unless {@code OPENAI_API_KEY} is set.
 */
@SpringBootTest
@Tag("live-openai")
@EnabledIfEnvironmentVariable(named = "OPENAI_API_KEY", matches = ".+")
class IngestServiceIntegrationTest extends PgVectorContainerTest {

    @Autowired private IngestService ingestService;
    @Autowired private ChunkRepository chunkRepository;
    @Autowired private DocumentRepository documentRepository;

    private Document ingested;

    @AfterEach
    void cleanup() {
        if (ingested != null) {
            documentRepository.deleteById(ingested.getId());
        }
    }

    @Test
    void ingestsTextAndStores1536DimEmbeddings() {
        String text =
                "Spring AI provides building blocks for retrieval-augmented generation. "
                        .repeat(40);

        ingested = ingestService.ingestText("Spring AI overview", text);

        assertThat(ingested.getStatus()).isEqualTo(DocumentStatus.READY);

        List<DocumentChunk> chunks =
                chunkRepository.findByDocument_IdOrderByChunkIndex(ingested.getId());
        assertThat(chunks).isNotEmpty();
        assertThat(chunks.get(0).getChunkIndex()).isZero();
        assertThat(chunks.get(0).getContent()).isNotBlank();
        assertThat(chunks.get(0).getEmbedding()).hasSize(1536);
    }
}
