package personal.knowledge.base.ingest;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.RejectedExecutionException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;
import personal.knowledge.base.domain.Document;
import personal.knowledge.base.domain.SourceType;

/** Creates PENDING documents and hands them to the bounded ingest executor. */
@Service
@RequiredArgsConstructor
@Slf4j
public class IngestJobService {

    private final IngestLifecycleService lifecycleService;
    private final IngestService ingestService;
    private final ThreadPoolTaskExecutor ingestExecutor;

    public Document submitText(String title, String text) {
        return submit(title, SourceType.TEXT, text.getBytes(StandardCharsets.UTF_8));
    }

    public Document submitUrl(String url) {
        return submit(url, SourceType.URL, url.getBytes(StandardCharsets.UTF_8));
    }

    public Document submitPdf(String filename, byte[] bytes) {
        return submit(filename, SourceType.PDF, bytes);
    }

    private Document submit(String title, SourceType sourceType, byte[] payload) {
        Document document = lifecycleService.createPending(title, sourceType, payload);
        enqueue(document.getId());
        return document;
    }

    /** Submits a document for processing; a saturated executor is retried by the periodic sweep. */
    public void enqueue(UUID documentId) {
        try {
            ingestExecutor.execute(() -> ingestService.process(documentId));
        } catch (RejectedExecutionException e) {
            log.debug("Ingest executor saturated; document {} stays PENDING for the next sweep", documentId);
        }
    }
}
