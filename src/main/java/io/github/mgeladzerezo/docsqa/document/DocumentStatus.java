package io.github.mgeladzerezo.docsqa.document;

/**
 * Stage of a document's ingestion. The first four are in-flight stages a worker can resume from;
 * READY and FAILED are terminal.
 */
public enum DocumentStatus {
    QUEUED,
    EXTRACTING,
    CHUNKING,
    EMBEDDING,
    READY,
    FAILED;

    public boolean isTerminal() {
        return this == READY || this == FAILED;
    }
}
