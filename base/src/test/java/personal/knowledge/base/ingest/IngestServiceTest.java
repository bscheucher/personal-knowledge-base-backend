package personal.knowledge.base.ingest;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import personal.knowledge.base.domain.Document;
import personal.knowledge.base.domain.DocumentStatus;
import personal.knowledge.base.domain.SourceType;

class IngestServiceTest {
    private final ChunkingService chunkingService = mock(ChunkingService.class);
    private final EmbeddingService embeddingService = mock(EmbeddingService.class);
    private final UrlFetchingService urlFetchingService = mock(UrlFetchingService.class);
    private final IngestLifecycleService lifecycleService = mock(IngestLifecycleService.class);
    private final IngestProperties properties = new IngestProperties();
    private IngestService service;
    private Document pending;

    @BeforeEach
    void setUp() {
        service =
                new IngestService(
                        chunkingService,
                        embeddingService,
                        urlFetchingService,
                        lifecycleService,
                        properties);
        pending =
                Document.builder()
                        .id(UUID.randomUUID())
                        .title("Test")
                        .sourceType(SourceType.TEXT)
                        .status(DocumentStatus.PENDING)
                        .build();
        given(lifecycleService.createPending(any(), any())).willReturn(pending);
    }

    @Test
    void completesOnlyAfterValidEmbeddings() {
        List<String> contents = List.of("first", "second");
        List<float[]> embeddings = List.of(vector(1536), vector(1536));
        Document ready = Document.builder().id(pending.getId()).status(DocumentStatus.READY).build();
        given(chunkingService.chunk("text")).willReturn(contents);
        given(embeddingService.embed(contents)).willReturn(embeddings);
        given(lifecycleService.complete(pending.getId(), contents, embeddings)).willReturn(ready);

        service.ingestText("Test", "text");

        verify(lifecycleService).markProcessing(pending.getId());
        verify(lifecycleService).complete(pending.getId(), contents, embeddings);
        verify(lifecycleService, never()).fail(any(), any());
    }

    @Test
    void extractionFailureLeavesAnErrorRecord() {
        given(urlFetchingService.fetch("https://example.com"))
                .willThrow(new IngestException("The URL could not be fetched safely"));

        assertThatThrownBy(() -> service.ingestUrl("https://example.com"))
                .isInstanceOf(IngestException.class);

        verify(lifecycleService).markProcessing(pending.getId());
        verify(lifecycleService)
                .fail(pending.getId(), "The URL could not be fetched safely");
        verify(embeddingService, never()).embed(org.mockito.ArgumentMatchers.<List<String>>any());
    }

    @Test
    void embeddingCountMismatchWritesNothingAndFailsDocument() {
        List<String> contents = List.of("first", "second");
        given(chunkingService.chunk("text")).willReturn(contents);
        given(embeddingService.embed(contents)).willReturn(List.of(vector(1536)));

        assertThatThrownBy(() -> service.ingestText("Test", "text"))
                .isInstanceOf(IngestException.class)
                .hasMessage("Embedding service returned an invalid result count");

        verify(lifecycleService, never()).complete(any(), any(), any());
        verify(lifecycleService)
                .fail(pending.getId(), "Embedding service returned an invalid result count");
    }

    @Test
    void embeddingDimensionMismatchWritesNothingAndFailsDocument() {
        List<String> contents = List.of("first");
        given(chunkingService.chunk("text")).willReturn(contents);
        given(embeddingService.embed(contents)).willReturn(List.of(vector(12)));

        assertThatThrownBy(() -> service.ingestText("Test", "text"))
                .isInstanceOf(IngestException.class)
                .hasMessage("Embedding service returned an invalid vector dimension");

        verify(lifecycleService, never()).complete(any(), any(), any());
        verify(lifecycleService)
                .fail(pending.getId(), "Embedding service returned an invalid vector dimension");
    }

    @Test
    void chunkPersistenceFailureCleansUpAndUsesSafeReason() {
        List<String> contents = List.of("first");
        List<float[]> embeddings = List.of(vector(1536));
        given(chunkingService.chunk("text")).willReturn(contents);
        given(embeddingService.embed(contents)).willReturn(embeddings);
        given(lifecycleService.complete(pending.getId(), contents, embeddings))
                .willThrow(new RuntimeException("database host 10.0.0.5 unavailable"));

        assertThatThrownBy(() -> service.ingestText("Test", "text"))
                .isInstanceOf(IngestException.class)
                .hasMessage("Document ingestion failed");

        verify(lifecycleService).fail(pending.getId(), "Document processing failed");
    }

    @Test
    void boundsPersistedFailureReason() {
        properties.setMaxFailureReasonLength(10);
        given(urlFetchingService.fetch(any()))
                .willThrow(new IngestException("a user-safe but long failure reason"));

        assertThatThrownBy(() -> service.ingestUrl("https://example.com"))
                .isInstanceOf(IngestException.class);

        ArgumentCaptor<String> reason = ArgumentCaptor.forClass(String.class);
        verify(lifecycleService).fail(eq(pending.getId()), reason.capture());
        org.assertj.core.api.Assertions.assertThat(reason.getValue()).hasSize(10);
    }

    private static float[] vector(int dimensions) {
        return new float[dimensions];
    }
}
