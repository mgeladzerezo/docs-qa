package io.github.mgeladzerezo.docsqa.answer;

import java.util.UUID;

/**
 * A verified citation: marker {@code [n]} in the answer resolved to a document, a page and the exact
 * passage that supports it.
 *
 * @param n          the source number used in the answer text
 * @param page       1-based page on which the quote starts
 * @param quote      verbatim text from the document; always a substring of source {@code n}
 * @param quoteStart offset of the quote in the document text; subtract the page's {@code charStart}
 *                   to get its position inside the page text
 * @param quoteEnd   exclusive end offset in the document text
 */
public record Citation(
        int n,
        UUID documentId,
        String filename,
        String heading,
        int page,
        String quote,
        int quoteStart,
        int quoteEnd) {
}
