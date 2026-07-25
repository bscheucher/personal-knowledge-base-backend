package personal.knowledge.base.ingest;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import personal.knowledge.base.domain.Document;
import personal.knowledge.base.domain.DocumentChunk;
import personal.knowledge.base.domain.DocumentStatus;
import personal.knowledge.base.domain.SourceType;
import personal.knowledge.base.repository.ChunkRepository;
import personal.knowledge.base.repository.DocumentRepository;

/**
 * Owns the explicit transaction boundaries of the background document lifecycle.
 *
 * <p>{@code complete}/{@code fail}/{@code retryLater} all tolerate the document having been
 * deleted mid-flight (a delete is the cancellation signal for an in-flight job): they no-op
 * instead of throwing, so a worker can never resurrect chunks for a document that no longer
 * exists.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class IngestLifecycleService {
    private final DocumentRepository documentRepository;
    private final ChunkRepository chunkRepository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Document createPending(String title, SourceType sourceType, byte[] sourcePayload) {
        return documentRepository.save(
                Document.builder()
                        .title(title)
                        .sourceType(sourceType)
                        .status(DocumentStatus.PENDING)
                        .sourcePayload(sourcePayload)
                        .build());
    }

    /** Atomically claims the document for processing. Returns {@code false} if it lost the race. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean claim(UUID documentId) {
        return documentRepository.claimForProcessing(documentId, OffsetDateTime.now()) == 1;
    }

    /** Chunk insertion and READY transition commit or roll back as one unit. */
    @Transactional
    public Optional<Document> complete(UUID documentId, List<String> contents, List<float[]> embeddings) {
        Optional<Document> maybeDocument = documentRepository.findById(documentId);
        if (maybeDocument.isEmpty()) {
            log.debug("Document {} was deleted before ingestion completed; discarding result", documentId);
            return Optional.empty();
        }
        Document document = maybeDocument.get();
        List<DocumentChunk> chunks = new ArrayList<>(contents.size());
        for (int i = 0; i < contents.size(); i++) {
            chunks.add(
                    DocumentChunk.builder()
                            .document(document)
                            .chunkIndex(i)
                            .content(contents.get(i))
                            .embedding(embeddings.get(i))
                            .build());
        }
        chunkRepository.saveAll(chunks);
        // Flush chunk writes before exposing READY, so persistence errors roll back both changes.
        chunkRepository.flush();
        document.setStatus(DocumentStatus.READY);
        document.setFailureReason(null);
        document.setSourcePayload(null);
        return Optional.of(document);
    }

    /** Removes any partial chunks and records a bounded safe failure in one independent commit. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<Document> fail(UUID documentId, String failureReason) {
        chunkRepository.deleteByDocument_Id(documentId);
        Optional<Document> maybeDocument = documentRepository.findById(documentId);
        if (maybeDocument.isEmpty()) {
            log.debug("Document {} was deleted before ingestion failed; discarding result", documentId);
            return Optional.empty();
        }
        Document document = maybeDocument.get();
        document.setStatus(DocumentStatus.ERROR);
        document.setFailureReason(failureReason);
        document.setSourcePayload(null);
        return Optional.of(document);
    }

    /** Schedules another attempt: back to PENDING with a backoff-delayed eligibility time. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<Document> retryLater(UUID documentId, OffsetDateTime nextAttemptAt, String failureReason) {
        chunkRepository.deleteByDocument_Id(documentId);
        Optional<Document> maybeDocument = documentRepository.findById(documentId);
        if (maybeDocument.isEmpty()) {
            log.debug("Document {} was deleted before its retry could be scheduled", documentId);
            return Optional.empty();
        }
        Document document = maybeDocument.get();
        document.setStatus(DocumentStatus.PENDING);
        document.setNextAttemptAt(nextAttemptAt);
        document.setFailureReason(failureReason);
        return Optional.of(document);
    }

    /**
     * Recovers documents left in PROCESSING by a crashed worker: retried if attempts remain,
     * otherwise moved to ERROR. Runs once at startup.
     */
    @Transactional
    public int recoverInterrupted(int maxAttempts, String failureReason) {
        List<Document> interrupted = documentRepository.findByStatus(DocumentStatus.PROCESSING);
        for (Document document : interrupted) {
            chunkRepository.deleteByDocument_Id(document.getId());
            if (document.getAttemptCount() < maxAttempts) {
                document.setStatus(DocumentStatus.PENDING);
                document.setNextAttemptAt(null);
                document.setFailureReason(failureReason);
            } else {
                document.setStatus(DocumentStatus.ERROR);
                document.setFailureReason(failureReason);
                document.setSourcePayload(null);
            }
        }
        return interrupted.size();
    }
}
