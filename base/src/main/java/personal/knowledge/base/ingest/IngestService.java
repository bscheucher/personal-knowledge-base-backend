package personal.knowledge.base.ingest;

import java.io.IOException;
import java.util.List;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;
import personal.knowledge.base.domain.Document;
import personal.knowledge.base.domain.SourceType;

/**
 * Orchestrates the ingest pipeline: text extraction → chunking → embedding → storage.
 *
 * <p>Runs synchronously and drives the document through its status lifecycle
 * ({@code PENDING → PROCESSING → READY}, or {@code ERROR} on failure). Status writes are
 * intentionally committed independently of the body so that an {@code ERROR} state
 * survives a failed embedding or parse.
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
    private final IngestProperties properties;

    /** Ingests raw text directly. */
    public Document ingestText(String title, String text) {
        return ingest(title, SourceType.TEXT, () -> text);
    }

    /** Securely fetches a URL, extracts its visible text, and ingests it. */
    public Document ingestUrl(String url) {
        return ingest(url, SourceType.URL, () -> urlFetchingService.fetch(url).text());
    }

    /** Extracts text from a PDF with PDFBox and ingests it. */
    public Document ingestPdf(String filename, byte[] bytes) {
        return ingest(filename, SourceType.PDF, () -> extractPdf(filename, bytes));
    }

    private String extractPdf(String filename, byte[] bytes) {
        try (PDDocument pdf = Loader.loadPDF(bytes)) {
            return new PDFTextStripper().getText(pdf);
        } catch (IOException e) {
            throw new IngestException("Failed to parse PDF: " + filename, e);
        }
    }

    private Document ingest(String title, SourceType sourceType, Supplier<String> extractor) {
        Document document = lifecycleService.createPending(title, sourceType);
        try {
            lifecycleService.markProcessing(document.getId());

            String rawText = extractor.get();
            List<String> contents = chunkingService.chunk(rawText);
            if (contents.isEmpty()) {
                throw new IngestException("No text content extracted from: " + title);
            }

            List<float[]> embeddings = embeddingService.embed(contents);
            validateEmbeddings(contents, embeddings);

            Document ready = lifecycleService.complete(document.getId(), contents, embeddings);
            log.info("Ingested document {} ({}) with {} chunks", ready.getId(), title, contents.size());
            return ready;
        } catch (Exception e) {
            log.error("Ingest failed for document {} ({})", document.getId(), title, e);
            lifecycleService.fail(document.getId(), safeFailureReason(e));
            throw (e instanceof IngestException ie)
                    ? ie
                    : new IngestException("Document ingestion failed", e);
        }
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
