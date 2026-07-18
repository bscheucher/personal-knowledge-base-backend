package personal.knowledge.base.ingest;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Service;
import personal.knowledge.base.domain.Document;
import personal.knowledge.base.domain.DocumentChunk;
import personal.knowledge.base.domain.DocumentStatus;
import personal.knowledge.base.domain.SourceType;
import personal.knowledge.base.repository.ChunkRepository;
import personal.knowledge.base.repository.DocumentRepository;

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
public class IngestService {

    private final DocumentRepository documentRepository;
    private final ChunkRepository chunkRepository;
    private final ChunkingService chunkingService;
    private final EmbeddingService embeddingService;
    private final UrlFetchingService urlFetchingService;

    /** Ingests raw text directly. */
    public Document ingestText(String title, String text) {
        return ingest(title, SourceType.TEXT, text);
    }

    /** Securely fetches a URL, extracts its visible text, and ingests it. */
    public Document ingestUrl(String url) {
        UrlFetchingService.FetchedPage page = urlFetchingService.fetch(url);
        return ingest(page.uri().toString(), SourceType.URL, page.text());
    }

    /** Extracts text from a PDF with PDFBox and ingests it. */
    public Document ingestPdf(String filename, byte[] bytes) {
        String text;
        try (PDDocument pdf = Loader.loadPDF(bytes)) {
            text = new PDFTextStripper().getText(pdf);
        } catch (IOException e) {
            throw new IngestException("Failed to parse PDF: " + filename, e);
        }
        return ingest(filename, SourceType.PDF, text);
    }

    private Document ingest(String title, SourceType sourceType, String rawText) {
        Document document =
                documentRepository.save(
                        Document.builder()
                                .title(title)
                                .sourceType(sourceType)
                                .status(DocumentStatus.PENDING)
                                .build());
        try {
            document.setStatus(DocumentStatus.PROCESSING);
            documentRepository.save(document);

            List<String> contents = chunkingService.chunk(rawText);
            if (contents.isEmpty()) {
                throw new IngestException("No text content extracted from: " + title);
            }

            List<float[]> embeddings = embeddingService.embed(contents);
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

            document.setStatus(DocumentStatus.READY);
            Document ready = documentRepository.save(document);
            log.info("Ingested document {} ({}) with {} chunks", ready.getId(), title, chunks.size());
            return ready;
        } catch (Exception e) {
            log.error("Ingest failed for document {} ({})", document.getId(), title, e);
            document.setStatus(DocumentStatus.ERROR);
            documentRepository.save(document);
            throw (e instanceof IngestException ie) ? ie : new IngestException("Ingest failed: " + title, e);
        }
    }
}
