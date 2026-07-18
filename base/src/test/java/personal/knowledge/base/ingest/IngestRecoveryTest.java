package personal.knowledge.base.ingest;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.time.Duration;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class IngestRecoveryTest {
    @Test
    void marksOnlyDocumentsOlderThanConfiguredThresholdAsInterrupted() {
        IngestLifecycleService lifecycle = mock(IngestLifecycleService.class);
        IngestProperties properties = new IngestProperties();
        properties.setStaleAfter(Duration.ofMinutes(45));
        IngestRecovery recovery = new IngestRecovery(lifecycle, properties);

        OffsetDateTime before = OffsetDateTime.now();
        recovery.recoverStaleDocuments();
        OffsetDateTime after = OffsetDateTime.now();

        ArgumentCaptor<OffsetDateTime> cutoff = ArgumentCaptor.forClass(OffsetDateTime.class);
        verify(lifecycle)
                .failStaleDocuments(cutoff.capture(), eq(IngestRecovery.FAILURE_REASON));
        org.assertj.core.api.Assertions.assertThat(cutoff.getValue())
                .isBetween(before.minusMinutes(45), after.minusMinutes(45));
    }
}
