package io.github.mgeladzerezo.docsqa.ingest;

/**
 * Estimates how many model tokens a piece of text costs. The chunker only needs sizes to be additive
 * and roughly right, so it depends on this interface instead of on one model's tokenizer; the
 * embedding model can then be swapped without touching chunking.
 */
@FunctionalInterface
public interface TokenEstimator {

    int count(CharSequence text);

    /**
     * A WordPiece-style estimate without a vocabulary: one token per punctuation mark, one per short
     * word, and one more for roughly every six further characters of a long word or number, because
     * sub-word tokenizers split rare long words into several pieces.
     *
     * <p>It deliberately errs high. The evaluation build compares it with the real all-MiniLM-L6-v2
     * tokenizer on the sample corpus and fails if any chunk would be truncated by the model.
     */
    TokenEstimator HEURISTIC = text -> {
        int tokens = 0;
        int run = 0;
        for (int i = 0; i <= text.length(); i++) {
            char c = i < text.length() ? text.charAt(i) : ' ';
            if (Character.isLetterOrDigit(c)) {
                run++;
                continue;
            }
            if (run > 0) {
                tokens += 1 + (run - 1) / 6;
                run = 0;
            }
            if (!Character.isWhitespace(c)) {
                tokens++;
            }
        }
        return tokens;
    };
}
