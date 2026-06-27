package personal.knowledge.base.document;

import jakarta.validation.Valid;
import java.io.IOException;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import personal.knowledge.base.document.dto.DocumentResponse;
import personal.knowledge.base.document.dto.TextIngestRequest;
import personal.knowledge.base.document.dto.UrlIngestRequest;
import personal.knowledge.base.domain.Document;
import personal.knowledge.base.ingest.IngestException;
import personal.knowledge.base.ingest.IngestService;
import personal.knowledge.base.repository.DocumentRepository;

@RestController
@RequestMapping("/api/documents")
@RequiredArgsConstructor
public class DocumentController {

    private final IngestService ingestService;
    private final DocumentRepository documentRepository;

    /** Uploads and ingests a PDF file. */
    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public DocumentResponse upload(@RequestParam("file") MultipartFile file) {
        if (file.isEmpty()) {
            throw new IngestException("Uploaded file is empty");
        }
        String filename =
                file.getOriginalFilename() != null ? file.getOriginalFilename() : "upload.pdf";
        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException e) {
            throw new IngestException("Failed to read uploaded file: " + filename, e);
        }
        return DocumentResponse.from(ingestService.ingestPdf(filename, bytes));
    }

    /** Ingests a document from a URL. */
    @PostMapping("/url")
    public DocumentResponse ingestUrl(@Valid @RequestBody UrlIngestRequest request) {
        return DocumentResponse.from(ingestService.ingestUrl(request.url()));
    }

    /** Ingests raw text. */
    @PostMapping("/text")
    public DocumentResponse ingestText(@Valid @RequestBody TextIngestRequest request) {
        String title =
                (request.title() == null || request.title().isBlank())
                        ? "Untitled text"
                        : request.title();
        return DocumentResponse.from(ingestService.ingestText(title, request.text()));
    }

    /** Lists all documents, newest first. */
    @GetMapping
    public List<DocumentResponse> list() {
        return documentRepository.findAllByOrderByCreatedAtDesc().stream()
                .map(DocumentResponse::from)
                .toList();
    }

    /** Deletes a document and its chunks (chunks cascade at the database level). */
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id) {
        if (!documentRepository.existsById(id)) {
            throw new DocumentNotFoundException(id);
        }
        documentRepository.deleteById(id);
    }
}
