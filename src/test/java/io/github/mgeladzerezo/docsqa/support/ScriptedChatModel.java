package io.github.mgeladzerezo.docsqa.support;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

/**
 * A chat model that says what a test script tells it to. It records every prompt, streams its reply
 * in small pieces like a real model, and reports usage and a stop reason on the last piece.
 */
public class ScriptedChatModel implements ChatModel {

    public static final int PIECE_SIZE = 7;

    private final Function<Prompt, String> script;
    private final List<Prompt> prompts = new ArrayList<>();

    public ScriptedChatModel(Function<Prompt, String> script) {
        this.script = script;
    }

    public synchronized List<Prompt> prompts() {
        return List.copyOf(prompts);
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(reply(prompt)))));
    }

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        String reply = reply(prompt);
        List<ChatResponse> pieces = new ArrayList<>();
        for (int i = 0; i < reply.length(); i += PIECE_SIZE) {
            pieces.add(new ChatResponse(List.of(
                    new Generation(new AssistantMessage(reply.substring(i, Math.min(reply.length(), i + PIECE_SIZE)))))));
        }
        pieces.add(new ChatResponse(
                List.of(new Generation(new AssistantMessage(""),
                        ChatGenerationMetadata.builder().finishReason("end_turn").build())),
                ChatResponseMetadata.builder().model("scripted-model").usage(new DefaultUsage(321, 45)).build()));
        return Flux.fromIterable(pieces);
    }

    private synchronized String reply(Prompt prompt) {
        prompts.add(prompt);
        return script.apply(prompt);
    }
}
