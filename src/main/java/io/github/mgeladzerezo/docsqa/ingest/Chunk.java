package io.github.mgeladzerezo.docsqa.ingest;

/**
 * A retrievable passage. {@code content} is exactly {@code documentText.substring(charStart, charEnd)},
 * which is what lets a citation be shown highlighted in its page.
 *
 * @param ordinal    position of the chunk in the document, starting at 0
 * @param heading    the nearest heading above the chunk, or null before the first heading
 * @param pageStart  1-based page containing the first character
 * @param pageEnd    1-based page containing the last character
 * @param tokenCount estimated tokens of {@code content}
 */
public record Chunk(
        int ordinal,
        String content,
        String heading,
        int pageStart,
        int pageEnd,
        int charStart,
        int charEnd,
        int tokenCount) {

    /**
     * The text that is embedded: the heading is prepended when the chunk does not already begin with
     * it, so a passage from the middle of a section still carries what the section is about.
     */
    public String embeddingText() {
        return embeddingText(heading, content);
    }

    public static String embeddingText(String heading, String content) {
        if (heading == null || heading.isBlank() || content.contains(heading)) {
            return content;
        }
        return heading + "\n" + content;
    }
}
