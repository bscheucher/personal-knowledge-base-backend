package personal.knowledge.base.document;

import java.util.UUID;

/** Raised when a document referenced by id does not exist. */
public class DocumentNotFoundException extends RuntimeException {

    public DocumentNotFoundException(UUID id) {
        super("Document not found: " + id);
    }
}
