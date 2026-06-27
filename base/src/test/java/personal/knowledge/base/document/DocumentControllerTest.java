package personal.knowledge.base.document;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import personal.knowledge.base.domain.Document;
import personal.knowledge.base.domain.DocumentStatus;
import personal.knowledge.base.domain.SourceType;
import personal.knowledge.base.ingest.IngestException;
import personal.knowledge.base.ingest.IngestService;
import personal.knowledge.base.repository.DocumentRepository;

@WebMvcTest(DocumentController.class)
class DocumentControllerTest {

    @Autowired private MockMvc mockMvc;

    @MockitoBean private IngestService ingestService;
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
    void ingestTextReturnsDocument() throws Exception {
        given(ingestService.ingestText(eq("Notes"), any()))
                .willReturn(doc(SourceType.TEXT, DocumentStatus.READY, "Notes"));

        mockMvc.perform(
                        post("/api/documents/text")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"title\":\"Notes\",\"text\":\"hello world\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Notes"))
                .andExpect(jsonPath("$.sourceType").value("TEXT"))
                .andExpect(jsonPath("$.status").value("READY"));
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
    void ingestUrlFailureMapsToUnprocessableEntity() throws Exception {
        given(ingestService.ingestUrl(any()))
                .willThrow(new IngestException("Failed to fetch URL: http://x"));

        mockMvc.perform(
                        post("/api/documents/url")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"url\":\"http://x\"}"))
                .andExpect(status().isUnprocessableEntity());
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
