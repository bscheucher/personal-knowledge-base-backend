package personal.knowledge.base.document.dto;

import java.time.OffsetDateTime;
import java.util.UUID;
import personal.knowledge.base.domain.Document;
import personal.knowledge.base.domain.DocumentStatus;
import personal.knowledge.base.domain.SourceType;

/** API view of a {@link Document}. */
public record DocumentResponse(
        UUID id,
        String title,
        SourceType sourceType,
        DocumentStatus status,
        OffsetDateTime createdAt) {

    public static DocumentResponse from(Document document) {
        return new DocumentResponse(
                document.getId(),
                document.getTitle(),
                document.getSourceType(),
                document.getStatus(),
                document.getCreatedAt());
    }
}
