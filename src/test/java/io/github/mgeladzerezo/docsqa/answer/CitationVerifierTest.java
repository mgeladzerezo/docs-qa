package io.github.mgeladzerezo.docsqa.answer;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.mgeladzerezo.docsqa.embedding.EmbeddingGateway;
import io.github.mgeladzerezo.docsqa.support.HashingEmbeddingModel;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** A citation is only kept when it points at a source that was really supplied, and it resolves to a verbatim quote. */
class CitationVerifierTest {

    private static final UUID DOC_A = UUID.randomUUID();
    private static final UUID DOC_B = UUID.randomUUID();
    private static final String TEXT_1 = "Meals are reimbursed up to 60 EUR per day. Alcohol is never reimbursed. Tips are included.";
    private static final String TEXT_2 = "The espresso machine must be descaled every two months. Use only citric acid.";

    private final CitationVerifier verifier = new CitationVerifier(
            new SentenceRanker(new EmbeddingGateway(new HashingEmbeddingModel(), "fake", 384, 1)),
            // page = 10 + offset / 1000, so a multi-page source maps offsets to pages predictably
            (document, offset) -> 10 + offset / 1000);

    private final List<Source> sources = List.of(
            new Source(1, DOC_A, "policy.pdf", "Meals", 4, 4, 2000, 2000 + TEXT_1.length(), TEXT_1, 0.8, 1, 1, 0.03),
            new Source(2, DOC_B, "manual.pdf", "Care", 7, 8, 500, 500 + TEXT_2.length(), TEXT_2, 0.7, 2, 2, 0.02));

    @Test
    void aValidCitationResolvesToDocumentPageAndAVerbatimQuote() {
        CitationVerifier.Verified verified = verifier.verify("Alcohol is never reimbursed [1].", sources);

        assertThat(verified.invalid()).isEmpty();
        assertThat(verified.notFound()).isFalse();
        assertThat(verified.citations()).hasSize(1);
        Citation citation = verified.citations().getFirst();
        assertThat(citation.n()).isEqualTo(1);
        assertThat(citation.documentId()).isEqualTo(DOC_A);
        assertThat(citation.filename()).isEqualTo("policy.pdf");
        assertThat(citation.page()).isEqualTo(4);
        assertThat(citation.quote()).isEqualTo("Alcohol is never reimbursed.");
        assertThat(TEXT_1.substring(citation.quoteStart() - 2000, citation.quoteEnd() - 2000)).isEqualTo(citation.quote());
    }

    @Test
    void citationNumbersThatWereNeverSuppliedAreRemovedAndReported() {
        CitationVerifier.Verified verified = verifier.verify(
                "Descale every two months [2]. The warranty lasts two years [7]. See also [0].", sources);

        assertThat(verified.invalid()).containsExactly(0, 7);
        assertThat(verified.answer()).doesNotContain("[7]").doesNotContain("[0]").contains("[2]");
        assertThat(verified.answer()).contains("two years.");
        assertThat(verified.citations()).extracting(Citation::n).containsExactly(2);
    }

    @Test
    void groupedAndRangedMarkersAreExpandedAndNormalised() {
        CitationVerifier.Verified verified = verifier.verify("Both rules apply [1, 2].", sources);
        assertThat(verified.answer()).isEqualTo("Both rules apply [1][2].");
        assertThat(verified.citations()).extracting(Citation::n).containsExactly(1, 2);

        CitationVerifier.Verified ranged = verifier.verify("Both rules apply [1-3].", sources);
        assertThat(ranged.invalid()).containsExactly(3);
        assertThat(ranged.answer()).isEqualTo("Both rules apply [1][2].");
    }

    @Test
    void aYearInBracketsIsNotMistakenForACitation() {
        CitationVerifier.Verified verified = verifier.verify("The policy changed [2024] and meals are capped [1].", sources);

        assertThat(verified.invalid()).isEmpty();
        assertThat(verified.answer()).contains("[2024]");
    }

    @Test
    void theQuoteComesFromTheSentenceThatCarriesTheMarker() {
        CitationVerifier.Verified verified = verifier.verify(
                "Meals are reimbursed up to 60 EUR per day [1]. The machine must be descaled every two months [2].", sources);

        assertThat(verified.citations()).extracting(Citation::quote)
                .containsExactly("Meals are reimbursed up to 60 EUR per day.", "The espresso machine must be descaled every two months.");
    }

    @Test
    void aQuoteInAMultiPageSourceGetsThePageItStartsOn() {
        String text = "First sentence of the passage sits on the earlier page. " + "x".repeat(10) + " "
                + "Descaling uses citric acid only and never vinegar.";
        List<Source> spanning = List.of(new Source(1, DOC_B, "manual.pdf", null, 7, 8, 1500, 1500 + text.length(), text, 0.7, 1, 1, 0.03));

        Citation citation = verifier.verify("Use citric acid only [1].", spanning).citations().getFirst();

        assertThat(citation.quote()).contains("citric acid");
        assertThat(citation.page()).isEqualTo(10 + citation.quoteStart() / 1000);
    }

    @Test
    void anAnswerWithoutAnyValidCitationIsFlaggedAsUncited() {
        CitationVerifier.Verified verified = verifier.verify("Meals are capped at 60 EUR.", sources);

        assertThat(verified.uncited()).isTrue();
        assertThat(verified.citations()).isEmpty();
    }

    @Test
    void theNotFoundSentenceIsRecognisedWithOrWithoutCitationMarkers() {
        assertThat(verifier.verify("Not found in the documents.", sources).notFound()).isTrue();
        assertThat(verifier.verify("not found in the documents [3]", sources).notFound()).isTrue();
        assertThat(verifier.verify("Not found in the documents.", sources).citations()).isEmpty();
    }
}
