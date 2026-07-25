package personal.knowledge.base.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.IntStream;
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
import personal.knowledge.base.domain.DocumentStatus;
import personal.knowledge.base.repository.DocumentRepository;
import personal.knowledge.base.support.PgVectorContainerTest;

/**
 * A pool of exactly one worker with no queue: a second submission must be rejected while the
 * first is in flight, but the document stays PENDING and the periodic sweep picks it up once
 * capacity frees up — no submission is ever lost.
 */
@SpringBootTest(
        properties = {
            "spring.ai.openai.api-key=test",
            "app.ingest-jobs.core-pool-size=1",
            "app.ingest-jobs.max-pool-size=1",
            "app.ingest-jobs.queue-capacity=0",
            "app.ingest-jobs.dispatch-interval=200ms"
        })
class IngestJobSaturationTest extends PgVectorContainerTest {

    @Autowired private IngestJobService ingestJobService;
    @Autowired private DocumentRepository documentRepository;

    private final List<Document> created = new ArrayList<>();

    @AfterEach
    void cleanup() {
        StubEmbeddingConfig.releaseLatch();
        created.forEach(document -> documentRepository.deleteById(document.getId()));
    }

    @Test
    void rejectedSubmissionStaysPendingAndIsPickedUpByTheNextSweep() {
        StubEmbeddingConfig.reset();

        Document first = ingestJobService.submitText("First", "blocks until released");
        created.add(first);
        await().atMost(Duration.ofSeconds(5)).until(StubEmbeddingConfig.callStarted::get);

        Document second = ingestJobService.submitText("Second", "rejected while the pool is busy");
        created.add(second);

        await().during(Duration.ofMillis(300))
                .atMost(Duration.ofSeconds(2))
                .until(() -> status(second.getId()) == DocumentStatus.PENDING);

        StubEmbeddingConfig.releaseLatch();

        await().atMost(Duration.ofSeconds(10)).until(() -> isTerminal(first.getId()));
        await().atMost(Duration.ofSeconds(10)).until(() -> isTerminal(second.getId()));

        assertThat(status(first.getId())).isEqualTo(DocumentStatus.READY);
        assertThat(status(second.getId())).isEqualTo(DocumentStatus.READY);
    }

    private DocumentStatus status(UUID id) {
        return documentRepository.findById(id).orElseThrow().getStatus();
    }

    private boolean isTerminal(UUID id) {
        DocumentStatus status = status(id);
        return status == DocumentStatus.READY || status == DocumentStatus.ERROR;
    }

    @TestConfiguration
    static class StubEmbeddingConfig {
        static final AtomicBoolean callStarted = new AtomicBoolean(false);
        static volatile CountDownLatch latch = new CountDownLatch(1);

        static void reset() {
            callStarted.set(false);
            latch = new CountDownLatch(1);
        }

        static void releaseLatch() {
            latch.countDown();
        }

        @Bean
        @Primary
        EmbeddingModel stubEmbeddingModel() {
            return new BlockingStubEmbeddingModel();
        }

        static float[] deterministicVector(String text) {
            float[] v = new float[1536];
            Random random = new Random(text.hashCode());
            for (int i = 0; i < v.length; i++) {
                v[i] = random.nextFloat();
            }
            return v;
        }

        static class BlockingStubEmbeddingModel implements EmbeddingModel {
            @Override
            public EmbeddingResponse call(EmbeddingRequest request) {
                if (!callStarted.getAndSet(true)) {
                    try {
                        latch.await(10, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
                List<String> inputs = request.getInstructions();
                List<Embedding> embeddings =
                        IntStream.range(0, inputs.size())
                                .mapToObj(i -> new Embedding(deterministicVector(inputs.get(i)), i))
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
