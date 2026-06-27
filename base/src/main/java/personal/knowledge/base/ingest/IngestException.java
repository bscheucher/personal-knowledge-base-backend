package personal.knowledge.base.ingest;

/** Raised when a document cannot be ingested (extraction, chunking, or embedding failure). */
public class IngestException extends RuntimeException {

    public IngestException(String message) {
        super(message);
    }

    public IngestException(String message, Throwable cause) {
        super(message, cause);
    }
}
