package personal.knowledge.base.ingest;

/** Raised when a document cannot be ingested (extraction, chunking, or embedding failure). */
public class IngestException extends RuntimeException {

    private final boolean retryable;

    public IngestException(String message) {
        this(message, null, false);
    }

    public IngestException(String message, Throwable cause) {
        this(message, cause, false);
    }

    public IngestException(String message, Throwable cause, boolean retryable) {
        super(message, cause);
        this.retryable = retryable;
    }

    /** Whether this failure is transient and worth retrying (e.g. a network timeout). */
    public boolean isRetryable() {
        return retryable;
    }
}
