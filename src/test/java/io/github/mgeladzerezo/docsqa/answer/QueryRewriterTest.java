package io.github.mgeladzerezo.docsqa.answer;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.mgeladzerezo.docsqa.answer.QueryRewriter.Rewrite;
import io.github.mgeladzerezo.docsqa.answer.QueryRewriter.Turn;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

class QueryRewriterTest {

    private static final List<Turn> HISTORY = List.of(
            new Turn("user", "What is the daily meal allowance for domestic trips?"),
            new Turn("assistant", "It is 60 EUR [1]."));

    @Test
    void withoutHistoryTheQuestionIsUsedAsIs() {
        Rewrite rewrite = new QueryRewriter(Optional.empty(), 1000).rewrite("What is the limit?", List.of());
        assertThat(rewrite).isEqualTo(new Rewrite("What is the limit?", "none"));
    }

    @Test
    void theHeuristicAppendsThePreviousQuestionToADependentFollowUp() {
        Rewrite rewrite = new QueryRewriter(Optional.empty(), 1000).rewrite("And for international trips?", HISTORY);

        assertThat(rewrite.method()).isEqualTo("heuristic");
        assertThat(rewrite.query()).startsWith("And for international trips?").contains("daily meal allowance");
    }

    @Test
    void aSelfContainedQuestionIsLeftAloneEvenWithHistory() {
        Rewrite rewrite = new QueryRewriter(Optional.empty(), 1000)
                .rewrite("How often must the espresso machine be descaled?", HISTORY);

        assertThat(rewrite).isEqualTo(new Rewrite("How often must the espresso machine be descaled?", "none"));
    }

    @Test
    void withAModelTheRewriteComesFromTheModelAndTheHistoryIsDelimitedAsData() {
        Prompt[] seen = new Prompt[1];
        ChatModel model = new ChatModel() {
            @Override
            public ChatResponse call(Prompt prompt) {
                seen[0] = prompt;
                return new ChatResponse(List.of(new Generation(
                        new AssistantMessage("What is the meal allowance for international trips?"))));
            }
        };

        Rewrite rewrite = new QueryRewriter(Optional.of(model), 1000).rewrite("And for international trips?", HISTORY);

        assertThat(rewrite).isEqualTo(new Rewrite("What is the meal allowance for international trips?", "model"));
        assertThat(seen[0].getUserMessage().getText()).contains("<conversation>").contains("Last question: And for international trips?");
    }

    @Test
    void whenTheModelFailsTheHeuristicTakesOver() {
        ChatModel broken = new ChatModel() {
            @Override
            public ChatResponse call(Prompt prompt) {
                throw new IllegalStateException("rate limited");
            }
        };

        Rewrite rewrite = new QueryRewriter(Optional.of(broken), 1000).rewrite("And for international trips?", HISTORY);

        assertThat(rewrite.method()).isEqualTo("heuristic");
    }
}
