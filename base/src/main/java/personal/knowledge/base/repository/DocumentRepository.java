package personal.knowledge.base.repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import personal.knowledge.base.domain.Document;
import personal.knowledge.base.domain.DocumentStatus;

public interface DocumentRepository extends JpaRepository<Document, UUID> {

    List<Document> findAllByOrderByCreatedAtDesc();

    List<Document> findByStatusInAndCreatedAtBefore(
            List<DocumentStatus> statuses, OffsetDateTime createdBefore);
}
