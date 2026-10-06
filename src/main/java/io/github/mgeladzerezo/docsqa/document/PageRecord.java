package io.github.mgeladzerezo.docsqa.document;

/**
 * The cleaned text of one page.
 *
 * @param charStart offset of the page's first character in the document text
 */
public record PageRecord(int pageNumber, int charStart, String content) {
}
