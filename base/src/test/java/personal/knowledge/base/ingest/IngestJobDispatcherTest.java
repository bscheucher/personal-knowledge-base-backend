package personal.knowledge.base.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Pageable;
import org.springframework.scheduling.TaskScheduler;
import personal.knowledge.base.repository.DocumentRepository;

class IngestJobDispatcherTest {

    private final DocumentRepository documentRepository = mock(DocumentRepository.class);
    private final IngestJobService jobService = mock(IngestJobService.class);
    private final IngestLifecycleService lifecycleService = mock(IngestLifecycleService.class);
    private final TaskScheduler taskScheduler = mock(TaskScheduler.class);

    private final IngestJobProperties properties = new IngestJobProperties();

    private final IngestJobDispatcher dispatcher =
            new IngestJobDispatcher(documentRepository, jobService, lifecycleService, properties, taskScheduler);

    @Test
    void onReadyRecoversInterruptedDocumentsAndSchedulesDispatch() {
        properties.setMaxAttempts(3);

        dispatcher.onReady();

        verify(lifecycleService).recoverInterrupted(3, IngestJobDispatcher.INTERRUPTED_REASON);
        verify(taskScheduler)
                .scheduleWithFixedDelay(any(Runnable.class), eq(properties.getDispatchInterval()));
    }

    @Test
    void dispatchEnqueuesEveryReadyPendingDocument() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        when(documentRepository.findPendingReadyIds(any(OffsetDateTime.class), any(Pageable.class)))
                .thenReturn(List.of(first, second));

        dispatcher.dispatch();

        verify(jobService).enqueue(first);
        verify(jobService).enqueue(second);
    }

    @Test
    void dispatchBoundsTheBatchToTheConfiguredSize() {
        properties.setDispatchBatchSize(7);
        when(documentRepository.findPendingReadyIds(any(OffsetDateTime.class), any(Pageable.class)))
                .thenReturn(List.of());

        dispatcher.dispatch();

        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(documentRepository).findPendingReadyIds(any(OffsetDateTime.class), pageable.capture());
        assertThat(pageable.getValue().getPageSize()).isEqualTo(7);
    }

    @Test
    void defaultDispatchIntervalIsFiveSeconds() {
        assertThat(properties.getDispatchInterval()).isEqualTo(Duration.ofSeconds(5));
    }
}
