package personal.knowledge.base.ingest;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;
import personal.knowledge.base.domain.Document;
import personal.knowledge.base.repository.DocumentRepository;

/**
 * Runs the ingest pipeline for a single claimed document: text extraction → chunking →
 * embedding → storage.
 *
 * <p>Called by the background executor ({@link IngestJobService}) for one document at a time.
 * Drives the document through its status lifecycle ({@code PENDING → PROCESSING → READY}, or
 * back to {@code PENDING} with a backoff delay for a retryable failure, or {@code ERROR} once
 * attempts are exhausted or the failure is deterministic).
 */
@Service
@RequiredArgsConstructor
@Slf4j
@EnableConfigurationProperties(IngestProperties.class)
public class IngestService {

    private final ChunkingService chunkingService;
    private final EmbeddingService embeddingService;
    private final UrlFetchingService urlFetchingService;
    private final IngestLifecycleService lifecycleService;
    private final DocumentRepository documentRepository;
    private final IngestProperties properties;
    private final IngestJobProperties jobProperties;

    /** Processes one PENDING document: claim, extract, chunk, embed, and persist the outcome. */
    public void process(UUID documentId) {
        if (!lifecycleService.claim(documentId)) {
            return;
        }
        Document document = documentRepository.findById(documentId).orElse(null);
        if (document == null) {
            log.debug("Document {} was deleted right after being claimed", documentId);
            return;
        }
        try {
            String rawText = extract(document);
            List<String> contents = chunkingService.chunk(rawText);
            if (contents.isEmpty()) {
                throw new IngestException("No text content extracted from: " + document.getTitle());
            }

            List<float[]> embeddings = embeddingService.embed(contents);
            validateEmbeddings(contents, embeddings);

            lifecycleService
                    .complete(documentId, contents, embeddings)
                    .ifPresentOrElse(
                            ready ->
                                    log.info(
                                            "Ingested document {} ({}) with {} chunks",
                                            ready.getId(),
                                            ready.getTitle(),
                                            contents.size()),
                            () -> log.debug("Document {} was deleted before ingestion completed", documentId));
        } catch (Exception e) {
            handleFailure(document, e);
        }
    }

    private String extract(Document document) {
        byte[] payload = document.getSourcePayload();
        return switch (document.getSourceType()) {
            case TEXT -> new String(payload, StandardCharsets.UTF_8);
            case URL -> urlFetchingService.fetch(new String(payload, StandardCharsets.UTF_8)).text();
            case PDF -> extractPdf(document.getTitle(), payload);
        };
    }

    private String extractPdf(String filename, byte[] bytes) {
        try (PDDocument pdf = Loader.loadPDF(bytes)) {
            return new PDFTextStripper().getText(pdf);
        } catch (IOException e) {
            throw new IngestException("Failed to parse PDF: " + filename, e);
        }
    }

    private void handleFailure(Document document, Exception e) {
        UUID documentId = document.getId();
        boolean retryable = !(e instanceof IngestException ie) || ie.isRetryable();
        int attempt = document.getAttemptCount();
        if (retryable && attempt < jobProperties.getMaxAttempts()) {
            Duration delay = backoff(attempt);
            log.warn(
                    "Ingest attempt {} failed for document {} ({}); retrying in {}",
                    attempt,
                    documentId,
                    document.getTitle(),
                    delay,
                    e);
            lifecycleService.retryLater(documentId, OffsetDateTime.now().plus(delay), safeFailureReason(e));
        } else {
            log.error("Ingest failed for document {} ({})", documentId, document.getTitle(), e);
            lifecycleService.fail(documentId, safeFailureReason(e));
        }
    }

    private Duration backoff(int attempt) {
        int exponent = Math.clamp(attempt - 1, 0, 20);
        Duration delay = jobProperties.getRetryInitialDelay().multipliedBy(1L << exponent);
        Duration cap = jobProperties.getRetryMaxDelay();
        return delay.compareTo(cap) > 0 ? cap : delay;
    }

    private void validateEmbeddings(List<String> contents, List<float[]> embeddings) {
        if (embeddings == null || embeddings.size() != contents.size()) {
            throw new IngestException("Embedding service returned an invalid result count");
        }
        int expectedDimensions = properties.getEmbeddingDimensions();
        for (float[] embedding : embeddings) {
            if (embedding == null || embedding.length != expectedDimensions) {
                throw new IngestException("Embedding service returned an invalid vector dimension");
            }
        }
    }

    private String safeFailureReason(Exception failure) {
        String reason =
                failure instanceof IngestException && failure.getMessage() != null
                        ? failure.getMessage()
                        : "Document processing failed";
        int maxLength = properties.getMaxFailureReasonLength();
        return reason.length() <= maxLength ? reason : reason.substring(0, maxLength);
    }
}
