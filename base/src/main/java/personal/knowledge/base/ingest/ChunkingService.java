package personal.knowledge.base.ingest;

import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * Splits text into fixed-size, overlapping character windows.
 *
 * <p>Naive but sufficient for v1; can be replaced with sentence-aware or semantic
 * chunking later without touching callers.
 */
@Service
public class ChunkingService {

    static final int CHUNK_SIZE = 500;
    static final int OVERLAP = 50;

    /**
     * Splits the given text into chunks of at most {@link #CHUNK_SIZE} characters,
     * each overlapping the previous by {@link #OVERLAP} characters. Blank input yields
     * an empty list.
     */
    public List<String> chunk(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        String normalized = text.strip();
        int step = CHUNK_SIZE - OVERLAP;
        List<String> chunks = new ArrayList<>();
        for (int start = 0; start < normalized.length(); start += step) {
            int end = Math.min(start + CHUNK_SIZE, normalized.length());
            String chunk = normalized.substring(start, end).strip();
            if (!chunk.isBlank()) {
                chunks.add(chunk);
            }
            if (end == normalized.length()) {
                break;
            }
        }
        return chunks;
    }
}
