package personal.knowledge.base.ingest;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import personal.knowledge.base.domain.Document;
import personal.knowledge.base.domain.DocumentChunk;
import personal.knowledge.base.domain.DocumentStatus;
import personal.knowledge.base.domain.SourceType;
import personal.knowledge.base.repository.ChunkRepository;
import personal.knowledge.base.repository.DocumentRepository;

/** Owns the explicit transaction boundaries of the synchronous document lifecycle. */
@Service
@RequiredArgsConstructor
public class IngestLifecycleService {
    private final DocumentRepository documentRepository;
    private final ChunkRepository chunkRepository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Document createPending(String title, SourceType sourceType) {
        return documentRepository.save(
                Document.builder()
                        .title(title)
                        .sourceType(sourceType)
                        .status(DocumentStatus.PENDING)
                        .build());
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markProcessing(UUID documentId) {
        Document document = requireDocument(documentId);
        document.setStatus(DocumentStatus.PROCESSING);
        document.setFailureReason(null);
    }

    /** Chunk insertion and READY transition commit or roll back as one unit. */
    @Transactional
    public Document complete(UUID documentId, List<String> contents, List<float[]> embeddings) {
        Document document = requireDocument(documentId);
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
        return document;
    }

    /** Removes any partial chunks and records a bounded safe failure in one independent commit. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Document fail(UUID documentId, String failureReason) {
        chunkRepository.deleteByDocument_Id(documentId);
        Document document = requireDocument(documentId);
        document.setStatus(DocumentStatus.ERROR);
        document.setFailureReason(failureReason);
        return document;
    }

    @Transactional
    public int failStaleDocuments(OffsetDateTime createdBefore, String failureReason) {
        List<Document> stale =
                documentRepository.findByStatusInAndCreatedAtBefore(
                        List.of(DocumentStatus.PENDING, DocumentStatus.PROCESSING), createdBefore);
        for (Document document : stale) {
            chunkRepository.deleteByDocument_Id(document.getId());
            document.setStatus(DocumentStatus.ERROR);
            document.setFailureReason(failureReason);
        }
        return stale.size();
    }

    private Document requireDocument(UUID id) {
        return documentRepository
                .findById(id)
                .orElseThrow(() -> new IllegalStateException("Ingest document no longer exists"));
    }
}
