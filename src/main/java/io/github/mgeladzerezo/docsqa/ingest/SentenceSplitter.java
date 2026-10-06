package io.github.mgeladzerezo.docsqa.ingest;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Splits a range of text into sentences that partition it exactly: the spans are contiguous, start at
 * the range start and end at the range end. Whitespace after a sentence belongs to that sentence, so
 * callers can cut at any span boundary without losing or duplicating a character.
 *
 * <p>Written by hand instead of using {@code java.text.BreakIterator} because the JDK iterator splits
 * after "e.g.", "Fig." or an initial such as "J. Smith", and a chunk boundary in those places is
 * exactly the mid-sentence cut the chunker promises to avoid.
 */
public final class SentenceSplitter {

    private static final Set<String> ABBREVIATIONS = Set.of(
            "e.g", "i.e", "etc", "vs", "cf", "approx", "ca", "no", "nos", "fig", "figs", "sec", "ch", "eq",
            "vol", "pp", "ed", "eds", "al", "mr", "mrs", "ms", "dr", "prof", "sr", "jr", "st", "inc", "ltd",
            "co", "corp", "dept", "est", "min", "max", "jan", "feb", "mar", "apr", "jun", "jul", "aug",
            "sep", "sept", "oct", "nov", "dec");

    private SentenceSplitter() {
    }

    /** A sentence as a half-open character range. */
    public record Span(int start, int end) {
    }

    public static List<Span> split(String text, int from, int to) {
        List<Span> spans = new ArrayList<>();
        int start = from;
        int i = from;
        while (i < to) {
            char c = text.charAt(i);
            if ((c == '.' || c == '!' || c == '?') && isBoundary(text, i, start, to)) {
                int next = skipClosers(text, i + 1, to);
                next = skipWhitespace(text, next, to);
                spans.add(new Span(start, next));
                start = next;
                i = next;
            } else {
                i++;
            }
        }
        if (start < to) {
            spans.add(new Span(start, to));
        }
        return spans;
    }

    private static boolean isBoundary(String text, int index, int sentenceStart, int to) {
        int afterClosers = skipClosers(text, index + 1, to);
        if (afterClosers >= to) {
            return true;
        }
        if (!Character.isWhitespace(text.charAt(afterClosers))) {
            // "3.5", "example.com", "v1.2": no space after the mark, so the sentence continues.
            return false;
        }
        int nextStart = skipWhitespace(text, afterClosers, to);
        if (nextStart >= to) {
            return true;
        }
        char next = text.charAt(nextStart);
        boolean startsLikeSentence = Character.isUpperCase(next) || Character.isDigit(next)
                || next == '"' || next == '\'' || next == '(' || next == '[' || next == '“' || next == '‘';
        if (!startsLikeSentence) {
            return false;
        }
        if (text.charAt(index) != '.') {
            return true;
        }
        String word = wordBefore(text, index, sentenceStart);
        if (word.isEmpty()) {
            return true;
        }
        if (ABBREVIATIONS.contains(word.toLowerCase())) {
            return false;
        }
        // "J. Smith": a single capital letter is an initial, not the end of a sentence.
        if (word.length() == 1 && Character.isUpperCase(word.charAt(0))) {
            return false;
        }
        // "1. Open the lid": a list number at the very start of a sentence.
        boolean listNumber = word.length() <= 2 && word.chars().allMatch(Character::isDigit)
                && index - word.length() == sentenceStart;
        return !listNumber;
    }

    /** The run of letters, digits and inner dots directly before {@code index}, e.g. "e.g" for "e.g.". */
    private static String wordBefore(String text, int index, int limit) {
        int start = index;
        while (start > limit && (Character.isLetterOrDigit(text.charAt(start - 1)) || text.charAt(start - 1) == '.')) {
            start--;
        }
        return text.substring(start, index);
    }

    private static int skipClosers(String text, int index, int to) {
        while (index < to) {
            char c = text.charAt(index);
            if (c == '"' || c == '\'' || c == ')' || c == ']' || c == '”' || c == '’'
                    || c == '.' || c == '!' || c == '?') {
                index++;
            } else {
                break;
            }
        }
        return index;
    }

    private static int skipWhitespace(String text, int index, int to) {
        while (index < to && Character.isWhitespace(text.charAt(index))) {
            index++;
        }
        return index;
    }
}
