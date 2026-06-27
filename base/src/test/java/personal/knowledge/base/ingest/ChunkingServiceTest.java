package personal.knowledge.base.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class ChunkingServiceTest {

    private final ChunkingService chunkingService = new ChunkingService();

    @Test
    void blankInputYieldsNoChunks() {
        assertThat(chunkingService.chunk(null)).isEmpty();
        assertThat(chunkingService.chunk("   ")).isEmpty();
    }

    @Test
    void shortTextYieldsSingleChunk() {
        List<String> chunks = chunkingService.chunk("hello world");
        assertThat(chunks).containsExactly("hello world");
    }

    @Test
    void longTextIsSplitIntoOverlappingChunks() {
        String text = "x".repeat(1200);
        List<String> chunks = chunkingService.chunk(text);

        // step = 500 - 50 = 450 -> starts at 0, 450, 900 => 3 chunks
        assertThat(chunks).hasSize(3);
        assertThat(chunks.get(0)).hasSize(ChunkingService.CHUNK_SIZE);
        assertThat(chunks.get(1)).hasSize(ChunkingService.CHUNK_SIZE);
        assertThat(chunks.get(2)).hasSize(1200 - 2 * 450); // 300
    }

    @Test
    void consecutiveChunksOverlap() {
        // Distinct characters so we can see the overlap region directly.
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 600; i++) {
            sb.append((char) ('a' + (i % 26)));
        }
        String text = sb.toString();

        List<String> chunks = chunkingService.chunk(text);
        assertThat(chunks).hasSize(2);

        String firstTail = chunks.get(0).substring(ChunkingService.CHUNK_SIZE - ChunkingService.OVERLAP);
        String secondHead = chunks.get(1).substring(0, ChunkingService.OVERLAP);
        assertThat(firstTail).isEqualTo(secondHead);
    }
}
