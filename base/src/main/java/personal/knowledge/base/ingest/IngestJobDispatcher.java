package personal.knowledge.base.ingest;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Component;
import personal.knowledge.base.repository.DocumentRepository;

/**
 * Recovers documents orphaned by a crash and periodically re-dispatches PENDING documents that
 * are ready: new arrivals the immediate {@link IngestJobService#enqueue} missed, backoff retries
 * whose delay elapsed, and anything the executor previously rejected as saturated.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class IngestJobDispatcher {

    static final String INTERRUPTED_REASON = "Ingestion was interrupted before completion";

    private final DocumentRepository documentRepository;
    private final IngestJobService jobService;
    private final IngestLifecycleService lifecycleService;
    private final IngestJobProperties properties;
    private final TaskScheduler taskScheduler;

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        int recovered =
                lifecycleService.recoverInterrupted(properties.getMaxAttempts(), INTERRUPTED_REASON);
        if (recovered > 0) {
            log.warn("Recovered {} document(s) interrupted by a previous shutdown", recovered);
        }
        taskScheduler.scheduleWithFixedDelay(this::dispatch, properties.getDispatchInterval());
    }

    void dispatch() {
        List<UUID> ready =
                documentRepository.findPendingReadyIds(
                        OffsetDateTime.now(), PageRequest.of(0, properties.getDispatchBatchSize()));
        ready.forEach(jobService::enqueue);
    }
}
