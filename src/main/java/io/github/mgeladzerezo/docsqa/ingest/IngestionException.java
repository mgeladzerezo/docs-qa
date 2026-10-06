package io.github.mgeladzerezo.docsqa.ingest;

/**
 * A document can never be ingested, however often it is retried: it is scanned, encrypted, corrupt
 * or over a limit. The message is shown to the user as the failure reason, so it must say what is
 * wrong and what to do about it.
 */
public class IngestionException extends RuntimeException {

    public IngestionException(String message) {
        super(message);
    }

    public IngestionException(String message, Throwable cause) {
        super(message, cause);
    }
}
