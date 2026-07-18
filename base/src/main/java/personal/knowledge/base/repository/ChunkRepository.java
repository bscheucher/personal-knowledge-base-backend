package personal.knowledge.base.repository;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import personal.knowledge.base.domain.DocumentChunk;

public interface ChunkRepository extends JpaRepository<DocumentChunk, UUID> {

    List<DocumentChunk> findByDocument_IdOrderByChunkIndex(UUID documentId);

    void deleteByDocument_Id(UUID documentId);

    /**
     * Retrieves the chunks most similar to the given query embedding, ordered by
     * ascending cosine distance ({@code <=>}), i.e. most relevant first. The no-op addition keeps
     * this query exact: an approximate IVFFlat scan applies joins as post-filters and can otherwise
     * return fewer than {@code limit} READY chunks, including no rows at all.
     *
     * @param embedding the query vector in pgvector literal form, e.g. {@code [0.1,0.2,...]}
     * @param limit     maximum number of chunks to return
     */
    @Query(
            value =
                    """
                    SELECT chunk.* FROM document_chunk chunk
                    JOIN document doc ON doc.id = chunk.document_id
                    WHERE doc.status = 'READY'
                    ORDER BY (chunk.embedding <=> CAST(:embedding AS vector)) + 0
                    LIMIT :limit
                    """,
            nativeQuery = true)
    List<DocumentChunk> findNearest(@Param("embedding") String embedding, @Param("limit") int limit);

    /** Convenience overload that formats a {@code float[]} into the pgvector literal. */
    default List<DocumentChunk> findNearest(float[] embedding, int limit) {
        return findNearest(toVectorLiteral(embedding), limit);
    }

    /** Formats an embedding as a pgvector literal, e.g. {@code [0.1,0.2,0.3]}. */
    static String toVectorLiteral(float[] embedding) {
        StringBuilder sb = new StringBuilder(embedding.length * 8).append('[');
        for (int i = 0; i < embedding.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(embedding[i]);
        }
        return sb.append(']').toString();
    }
}
