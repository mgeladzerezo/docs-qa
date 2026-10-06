package io.github.mgeladzerezo.docsqa.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class SentenceSplitterTest {

    @Test
    void splitsOnTerminalPunctuationFollowedByACapital() {
        assertThat(sentences("The tank is empty. Refill it! Is it seated? Yes."))
                .containsExactly("The tank is empty. ", "Refill it! ", "Is it seated? ", "Yes.");
    }

    @Test
    void doesNotSplitAfterAbbreviationsInitialsDecimalsOrListNumbers() {
        assertThat(sentences("Use a mild acid, e.g. Citric acid, at approx. 50 g per litre.")).hasSize(1);
        assertThat(sentences("Approved by Dr. Jane Q. Smith on arrival.")).hasSize(1);
        assertThat(sentences("The rate is 0.42 EUR per km. It applies up to 500 km."))
                .containsExactly("The rate is 0.42 EUR per km. ", "It applies up to 500 km.");
        assertThat(sentences("1. Open the valve. 2. Wait.")).containsExactly("1. Open the valve. ", "2. Wait.");
        assertThat(sentences("See bastion.lumen.internal for access. Then log in.")).hasSize(2);
    }

    @Test
    void keepsClosingQuotesAndBracketsWithTheirSentence() {
        assertThat(sentences("He said \"stop.\" Then he left. (This was expected.) Nobody minded."))
                .containsExactly("He said \"stop.\" ", "Then he left. ", "(This was expected.) ", "Nobody minded.");
    }

    @Test
    void spansPartitionTheRangeExactly() {
        String text = "xx First one.  Second one?   third stays with second. Fourth";
        List<SentenceSplitter.Span> spans = SentenceSplitter.split(text, 3, text.length());

        assertThat(spans.getFirst().start()).isEqualTo(3);
        assertThat(spans.getLast().end()).isEqualTo(text.length());
        for (int i = 1; i < spans.size(); i++) {
            assertThat(spans.get(i).start()).isEqualTo(spans.get(i - 1).end());
        }
        assertThat(spans).hasSize(3);
    }

    @Test
    void emptyRangeHasNoSentences() {
        assertThat(SentenceSplitter.split("abc", 1, 1)).isEmpty();
    }

    private static List<String> sentences(String text) {
        return SentenceSplitter.split(text, 0, text.length()).stream()
                .map(span -> text.substring(span.start(), span.end()))
                .toList();
    }
}
