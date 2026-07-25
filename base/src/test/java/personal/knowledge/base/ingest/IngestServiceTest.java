package personal.knowledge.base.ingest;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import personal.knowledge.base.domain.Document;
import personal.knowledge.base.domain.DocumentStatus;
import personal.knowledge.base.domain.SourceType;
import personal.knowledge.base.repository.DocumentRepository;

class IngestServiceTest {
    private final ChunkingService chunkingService = mock(ChunkingService.class);
    private final EmbeddingService embeddingService = mock(EmbeddingService.class);
    private final UrlFetchingService urlFetchingService = mock(UrlFetchingService.class);
    private final IngestLifecycleService lifecycleService = mock(IngestLifecycleService.class);
    private final DocumentRepository documentRepository = mock(DocumentRepository.class);
    private final IngestProperties properties = new IngestProperties();
    private final IngestJobProperties jobProperties = new IngestJobProperties();
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
                        documentRepository,
                        properties,
                        jobProperties);
        pending = textDocument(UUID.randomUUID(), "text", 1);
    }

    private Document textDocument(UUID id, String text, int attemptCount) {
        Document document =
                Document.builder()
                        .id(id)
                        .title("Test")
                        .sourceType(SourceType.TEXT)
                        .status(DocumentStatus.PROCESSING)
                        .attemptCount(attemptCount)
                        .sourcePayload(text.getBytes(StandardCharsets.UTF_8))
                        .build();
        given(lifecycleService.claim(id)).willReturn(true);
        given(documentRepository.findById(id)).willReturn(Optional.of(document));
        return document;
    }

    private Document urlDocument(UUID id, String url, int attemptCount) {
        Document document =
                Document.builder()
                        .id(id)
                        .title(url)
                        .sourceType(SourceType.URL)
                        .status(DocumentStatus.PROCESSING)
                        .attemptCount(attemptCount)
                        .sourcePayload(url.getBytes(StandardCharsets.UTF_8))
                        .build();
        given(lifecycleService.claim(id)).willReturn(true);
        given(documentRepository.findById(id)).willReturn(Optional.of(document));
        return document;
    }

    @Test
    void skipsProcessingWhenClaimIsLost() {
        given(lifecycleService.claim(pending.getId())).willReturn(false);

        service.process(pending.getId());

        verifyNoInteractions(chunkingService, embeddingService);
        verify(lifecycleService, never()).complete(any(), any(), any());
    }

    @Test
    void noOpsWhenDocumentDisappearsAfterClaim() {
        given(documentRepository.findById(pending.getId())).willReturn(Optional.empty());

        service.process(pending.getId());

        verifyNoInteractions(chunkingService, embeddingService);
    }

    @Test
    void completesOnlyAfterValidEmbeddings() {
        List<String> contents = List.of("first", "second");
        List<float[]> embeddings = List.of(vector(1536), vector(1536));
        Document ready = Document.builder().id(pending.getId()).status(DocumentStatus.READY).build();
        given(chunkingService.chunk("text")).willReturn(contents);
        given(embeddingService.embed(contents)).willReturn(embeddings);
        given(lifecycleService.complete(pending.getId(), contents, embeddings))
                .willReturn(Optional.of(ready));

        service.process(pending.getId());

        verify(lifecycleService).complete(pending.getId(), contents, embeddings);
        verify(lifecycleService, never()).fail(any(), any());
        verify(lifecycleService, never()).retryLater(any(), any(), any());
    }

    @Test
    void nonRetryableFailureFailsImmediatelyRegardlessOfAttemptsRemaining() {
        Document document = urlDocument(UUID.randomUUID(), "https://example.com", 1);
        given(urlFetchingService.fetch("https://example.com"))
                .willThrow(new IngestException("The URL could not be fetched safely"));

        service.process(document.getId());

        verify(lifecycleService).fail(document.getId(), "The URL could not be fetched safely");
        verify(lifecycleService, never()).retryLater(any(), any(), any());
        verify(embeddingService, never()).embed(org.mockito.ArgumentMatchers.<List<String>>any());
    }

    @Test
    void retryableFailureBelowMaxAttemptsSchedulesARetryInsteadOfFailing() {
        Document document = urlDocument(UUID.randomUUID(), "https://example.com", 1);
        given(urlFetchingService.fetch("https://example.com"))
                .willThrow(new IngestException("The URL could not be fetched safely", new IOException("timeout"), true));

        service.process(document.getId());

        ArgumentCaptor<OffsetDateTime> nextAttempt = ArgumentCaptor.forClass(OffsetDateTime.class);
        verify(lifecycleService)
                .retryLater(eq(document.getId()), nextAttempt.capture(), eq("The URL could not be fetched safely"));
        org.assertj.core.api.Assertions.assertThat(nextAttempt.getValue()).isAfter(OffsetDateTime.now());
        verify(lifecycleService, never()).fail(any(), any());
    }

    @Test
    void retryableFailureAtMaxAttemptsFailsInstead() {
        Document document = textDocument(UUID.randomUUID(), "text", jobProperties.getMaxAttempts());
        given(chunkingService.chunk("text"))
                .willThrow(new RuntimeException("database host 10.0.0.5 unavailable"));

        service.process(document.getId());

        verify(lifecycleService).fail(document.getId(), "Document processing failed");
        verify(lifecycleService, never()).retryLater(any(), any(), any());
    }

    @Test
    void embeddingCountMismatchWritesNothingAndFailsDocument() {
        List<String> contents = List.of("first", "second");
        given(chunkingService.chunk("text")).willReturn(contents);
        given(embeddingService.embed(contents)).willReturn(List.of(vector(1536)));

        service.process(pending.getId());

        verify(lifecycleService, never()).complete(any(), any(), any());
        verify(lifecycleService)
                .fail(pending.getId(), "Embedding service returned an invalid result count");
    }

    @Test
    void embeddingDimensionMismatchWritesNothingAndFailsDocument() {
        List<String> contents = List.of("first");
        given(chunkingService.chunk("text")).willReturn(contents);
        given(embeddingService.embed(contents)).willReturn(List.of(vector(12)));

        service.process(pending.getId());

        verify(lifecycleService, never()).complete(any(), any(), any());
        verify(lifecycleService)
                .fail(pending.getId(), "Embedding service returned an invalid vector dimension");
    }

    @Test
    void boundsPersistedFailureReason() {
        properties.setMaxFailureReasonLength(10);
        Document document = urlDocument(UUID.randomUUID(), "https://example.com", 1);
        given(urlFetchingService.fetch(any()))
                .willThrow(new IngestException("a user-safe but long failure reason"));

        service.process(document.getId());

        ArgumentCaptor<String> reason = ArgumentCaptor.forClass(String.class);
        verify(lifecycleService).fail(eq(document.getId()), reason.capture());
        org.assertj.core.api.Assertions.assertThat(reason.getValue()).hasSize(10);
    }

    private static float[] vector(int dimensions) {
        return new float[dimensions];
    }
}
