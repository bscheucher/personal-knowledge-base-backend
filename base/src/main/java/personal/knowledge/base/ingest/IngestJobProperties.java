package personal.knowledge.base.ingest;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** Sizing, scheduling, and retry bounds for background ingest processing. */
@ConfigurationProperties("app.ingest-jobs")
@Validated
@Getter
@Setter
public class IngestJobProperties {
    private boolean dispatcherEnabled = true;
    @Min(1) private int corePoolSize = 2;
    @Min(1) private int maxPoolSize = 4;
    @Min(0) private int queueCapacity = 50;
    @Min(1) private int dispatchBatchSize = 20;
    @NotNull private Duration dispatchInterval = Duration.ofSeconds(5);
    @Min(1) private int maxAttempts = 3;
    @NotNull private Duration retryInitialDelay = Duration.ofSeconds(5);
    @NotNull private Duration retryMaxDelay = Duration.ofMinutes(2);
}
