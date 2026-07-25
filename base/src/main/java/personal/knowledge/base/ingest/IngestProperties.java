package personal.knowledge.base.ingest;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** Correctness bounds for ingestion. */
@ConfigurationProperties("app.ingest")
@Validated
@Getter
@Setter
public class IngestProperties {
    @Min(1)
    private int embeddingDimensions = 1536;

    @Min(1)
    @Max(500)
    private int maxFailureReasonLength = 500;
}
