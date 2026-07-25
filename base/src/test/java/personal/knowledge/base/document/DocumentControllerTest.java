package personal.knowledge.base.document;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import personal.knowledge.base.domain.Document;
import personal.knowledge.base.domain.DocumentStatus;
import personal.knowledge.base.domain.SourceType;
import personal.knowledge.base.ingest.IngestJobService;
import personal.knowledge.base.repository.DocumentRepository;

@WebMvcTest(DocumentController.class)
class DocumentControllerTest {

    @Autowired private MockMvc mockMvc;

    @MockitoBean private IngestJobService ingestJobService;
    @MockitoBean private DocumentRepository documentRepository;

    private Document doc(SourceType type, DocumentStatus status, String title) {
        return Document.builder()
                .id(UUID.randomUUID())
                .title(title)
                .sourceType(type)
                .status(status)
                .build();
    }

    @Test
    void ingestTextReturnsAcceptedPendingDocument() throws Exception {
        given(ingestJobService.submitText(eq("Notes"), any()))
                .willReturn(doc(SourceType.TEXT, DocumentStatus.PENDING, "Notes"));

        mockMvc.perform(
                        post("/api/documents/text")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"title\":\"Notes\",\"text\":\"hello world\"}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.title").value("Notes"))
                .andExpect(jsonPath("$.sourceType").value("TEXT"))
                .andExpect(jsonPath("$.status").value("PENDING"));
    }

    @Test
    void ingestTextRejectsBlankText() throws Exception {
        mockMvc.perform(
                        post("/api/documents/text")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"text\":\"   \"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void ingestUrlReturnsAcceptedPendingDocument() throws Exception {
        given(ingestJobService.submitUrl(eq("http://example.com")))
                .willReturn(doc(SourceType.URL, DocumentStatus.PENDING, "http://example.com"));

        mockMvc.perform(
                        post("/api/documents/url")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"url\":\"http://example.com\"}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.sourceType").value("URL"))
                .andExpect(jsonPath("$.status").value("PENDING"));
    }

    @Test
    void uploadReturnsAcceptedPendingDocument() throws Exception {
        given(ingestJobService.submitPdf(eq("a.pdf"), any()))
                .willReturn(doc(SourceType.PDF, DocumentStatus.PENDING, "a.pdf"));

        MockMultipartFile file =
                new MockMultipartFile("file", "a.pdf", "application/pdf", "%PDF-1.4".getBytes());

        mockMvc.perform(multipart("/api/documents/upload").file(file))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.sourceType").value("PDF"))
                .andExpect(jsonPath("$.status").value("PENDING"));
    }

    @Test
    void uploadEmptyFileMapsToUnprocessableEntity() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "empty.pdf", "application/pdf", new byte[0]);

        mockMvc.perform(multipart("/api/documents/upload").file(file))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail").value("Uploaded file is empty"));
    }

    @Test
    void listReturnsDocuments() throws Exception {
        given(documentRepository.findAllByOrderByCreatedAtDesc())
                .willReturn(List.of(doc(SourceType.PDF, DocumentStatus.READY, "a.pdf")));

        mockMvc.perform(get("/api/documents"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].title").value("a.pdf"))
                .andExpect(jsonPath("$[0].sourceType").value("PDF"));
    }

    @Test
    void deleteExistingReturnsNoContent() throws Exception {
        UUID id = UUID.randomUUID();
        given(documentRepository.existsById(id)).willReturn(true);

        mockMvc.perform(delete("/api/documents/{id}", id)).andExpect(status().isNoContent());
        verify(documentRepository).deleteById(id);
    }

    @Test
    void deleteMissingReturnsNotFound() throws Exception {
        UUID id = UUID.randomUUID();
        given(documentRepository.existsById(id)).willReturn(false);

        mockMvc.perform(delete("/api/documents/{id}", id)).andExpect(status().isNotFound());
    }
}
