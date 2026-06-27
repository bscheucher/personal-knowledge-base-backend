package personal.knowledge.base.domain;

/** Lifecycle of a document through the ingest pipeline. */
public enum DocumentStatus {
    PENDING,
    PROCESSING,
    READY,
    ERROR
}
