package personal.knowledge.base.ingest;

import java.time.OffsetDateTime;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/** Marks abandoned synchronous ingests as failed after application startup. */
@Component
@RequiredArgsConstructor
@Slf4j
public class IngestRecovery {
    static final String FAILURE_REASON = "Ingestion was interrupted before completion";

    private final IngestLifecycleService lifecycleService;
    private final IngestProperties properties;

    @EventListener(ApplicationReadyEvent.class)
    public void recoverStaleDocuments() {
        int recovered =
                lifecycleService.failStaleDocuments(
                        OffsetDateTime.now().minus(properties.getStaleAfter()), FAILURE_REASON);
        if (recovered > 0) {
            log.warn("Marked {} stale ingest document(s) as ERROR", recovered);
        }
    }
}
