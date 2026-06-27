package personal.knowledge.base.repository;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import personal.knowledge.base.domain.Document;

public interface DocumentRepository extends JpaRepository<Document, UUID> {

    List<Document> findAllByOrderByCreatedAtDesc();
}
