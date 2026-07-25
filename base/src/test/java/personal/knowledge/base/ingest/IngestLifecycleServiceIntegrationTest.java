package personal.knowledge.base.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import personal.knowledge.base.domain.Document;
import personal.knowledge.base.domain.DocumentStatus;
import personal.knowledge.base.domain.SourceType;
import personal.knowledge.base.repository.ChunkRepository;
import personal.knowledge.base.repository.DocumentRepository;
import personal.knowledge.base.support.PgVectorContainerTest;

/** Exercises claiming and the deletion-race guards against a real database. */
@SpringBootTest(properties = "spring.ai.openai.api-key=test")
class IngestLifecycleServiceIntegrationTest extends PgVectorContainerTest {

    @Autowired private IngestLifecycleService lifecycleService;
    @Autowired private DocumentRepository documentRepository;
    @Autowired private ChunkRepository chunkRepository;

    private final List<Document> created = new ArrayList<>();

    @AfterEach
    void cleanup() {
        created.forEach(document -> documentRepository.deleteById(document.getId()));
        created.clear();
    }

    private Document pending() {
        Document document =
                lifecycleService.createPending(
                        "Concurrent claim", SourceType.TEXT, "hello".getBytes(StandardCharsets.UTF_8));
        created.add(document);
        return document;
    }

    private Document processing() {
        Document document =
                documentRepository.saveAndFlush(
                        Document.builder()
                                .title("Interrupted")
                                .sourceType(SourceType.TEXT)
                                .status(DocumentStatus.PROCESSING)
                                .attemptCount(1)
                                .build());
        created.add(document);
        return document;
    }

    @Test
    void claimIsWonByExactlyOneOfTwoConcurrentAttempts() throws Exception {
        Document document = pending();
        Callable<Boolean> attempt = () -> lifecycleService.claim(document.getId());

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<Boolean>> results = pool.invokeAll(List.of(attempt, attempt));
            long wins = results.stream().mapToInt(f -> get(f) ? 1 : 0).sum();
            assertThat(wins).isEqualTo(1);
        } finally {
            pool.shutdown();
        }

        assertThat(documentRepository.findById(document.getId()).orElseThrow().getStatus())
                .isEqualTo(DocumentStatus.PROCESSING);
        assertThat(documentRepository.findById(document.getId()).orElseThrow().getAttemptCount())
                .isEqualTo(1);
    }

    @Test
    void completeNoOpsWhenDocumentWasDeletedMidFlight() {
        Document document = pending();
        documentRepository.deleteById(document.getId());
        created.remove(document);

        Optional<Document> result =
                lifecycleService.complete(document.getId(), List.of("chunk"), List.of(new float[1536]));

        assertThat(result).isEmpty();
        assertThat(chunkRepository.findByDocument_IdOrderByChunkIndex(document.getId())).isEmpty();
    }

    @Test
    void failNoOpsWhenDocumentWasDeletedMidFlight() {
        Document document = pending();
        documentRepository.deleteById(document.getId());
        created.remove(document);

        Optional<Document> result = lifecycleService.fail(document.getId(), "boom");

        assertThat(result).isEmpty();
    }

    @Test
    void retryLaterNoOpsWhenDocumentWasDeletedMidFlight() {
        Document document = pending();
        documentRepository.deleteById(document.getId());
        created.remove(document);

        Optional<Document> result =
                lifecycleService.retryLater(document.getId(), java.time.OffsetDateTime.now(), "boom");

        assertThat(result).isEmpty();
    }

    @Test
    void recoverInterruptedResetsToPendingWhenAttemptsRemain() {
        Document document = processing();

        int recovered = lifecycleService.recoverInterrupted(3, "interrupted");

        assertThat(recovered).isGreaterThanOrEqualTo(1);
        Document reloaded = documentRepository.findById(document.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(DocumentStatus.PENDING);
        assertThat(reloaded.getFailureReason()).isEqualTo("interrupted");
    }

    @Test
    void recoverInterruptedFailsWhenAttemptsExhausted() {
        Document document =
                documentRepository.saveAndFlush(
                        Document.builder()
                                .title("Interrupted, exhausted")
                                .sourceType(SourceType.TEXT)
                                .status(DocumentStatus.PROCESSING)
                                .attemptCount(3)
                                .build());
        created.add(document);

        lifecycleService.recoverInterrupted(3, "interrupted");

        Document reloaded = documentRepository.findById(document.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(DocumentStatus.ERROR);
        assertThat(reloaded.getFailureReason()).isEqualTo("interrupted");
    }

    private static boolean get(Future<Boolean> future) {
        try {
            return future.get();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
