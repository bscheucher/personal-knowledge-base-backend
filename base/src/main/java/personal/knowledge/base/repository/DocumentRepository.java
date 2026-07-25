package personal.knowledge.base.repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import personal.knowledge.base.domain.Document;
import personal.knowledge.base.domain.DocumentStatus;

public interface DocumentRepository extends JpaRepository<Document, UUID> {

    List<Document> findAllByOrderByCreatedAtDesc();

    List<Document> findByStatus(DocumentStatus status);

    /** Atomically claims a document for processing; returns 1 if this call won the claim, else 0. */
    @Modifying
    @Query(
            """
            UPDATE Document d SET d.status = personal.knowledge.base.domain.DocumentStatus.PROCESSING,
                d.attemptCount = d.attemptCount + 1, d.failureReason = null
            WHERE d.id = :id AND d.status = personal.knowledge.base.domain.DocumentStatus.PENDING
                AND (d.nextAttemptAt IS NULL OR d.nextAttemptAt <= :now)
            """)
    int claimForProcessing(@Param("id") UUID id, @Param("now") OffsetDateTime now);

    @Query(
            """
            SELECT d.id FROM Document d
            WHERE d.status = personal.knowledge.base.domain.DocumentStatus.PENDING
                AND (d.nextAttemptAt IS NULL OR d.nextAttemptAt <= :now)
            ORDER BY d.createdAt
            """)
    List<UUID> findPendingReadyIds(@Param("now") OffsetDateTime now, Pageable pageable);
}
