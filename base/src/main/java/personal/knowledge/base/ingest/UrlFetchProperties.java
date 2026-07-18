package personal.knowledge.base.ingest;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;
import org.springframework.validation.annotation.Validated;

/** Tunable resource and redirect bounds for remote URL ingestion. */
@ConfigurationProperties("app.url-fetch")
@Validated
@Getter
@Setter
public class UrlFetchProperties {
    @NotNull private Duration connectTimeout = Duration.ofSeconds(5);
    @NotNull private Duration readTimeout = Duration.ofSeconds(10);
    @NotNull private DataSize maxResponseSize = DataSize.ofMegabytes(2);
    @Min(0) @Max(20) private int maxRedirects = 5;
    @NotBlank private String userAgent = "personal-knowledge-base/1.0";
}
