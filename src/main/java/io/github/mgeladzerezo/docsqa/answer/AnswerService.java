package io.github.mgeladzerezo.docsqa.answer;

import io.github.mgeladzerezo.docsqa.answer.AnswerEvent.Candidate;
import io.github.mgeladzerezo.docsqa.answer.AnswerEvent.Done;
import io.github.mgeladzerezo.docsqa.answer.AnswerEvent.Failure;
import io.github.mgeladzerezo.docsqa.answer.AnswerEvent.Sources;
import io.github.mgeladzerezo.docsqa.answer.AnswerEvent.Token;
import io.github.mgeladzerezo.docsqa.answer.AnswerEvent.Usage;
import io.github.mgeladzerezo.docsqa.answer.QueryRewriter.Rewrite;
import io.github.mgeladzerezo.docsqa.answer.QueryRewriter.Turn;
import io.github.mgeladzerezo.docsqa.config.AppProperties;
import io.github.mgeladzerezo.docsqa.retrieval.RetrievalService;
import io.github.mgeladzerezo.docsqa.retrieval.RetrievedChunk;
import io.github.mgeladzerezo.docsqa.retrieval.SearchRepository.Filter;
import io.github.mgeladzerezo.docsqa.web.ApiExceptionHandler.BadRequestException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;

/**
 * Answers one question: rewrite a follow-up, retrieve, then either let the model write a grounded
 * answer (verified afterwards) or, without a model, quote the best sentences.
 *
 * <p>Events are pushed to a consumer in the order documented on {@link AnswerEvent}. The consumer is
 * the SSE connection; when it throws (the client went away), generation is cancelled.
 */
public class AnswerService {

    public static final String GENERATIVE = "generative";
    public static final String EXTRACTIVE = "extractive";

    private static final Logger log = LoggerFactory.getLogger(AnswerService.class);
    private static final int MAX_TURN_CHARS = 4000;
    private static final int PREVIEW_CHARS = 180;

    private final RetrievalService retrieval;
    private final Optional<ChatModel> chatModel;
    private final QueryRewriter rewriter;
    private final CitationVerifier verifier;
    private final ExtractiveAnswerer extractive;
    private final AppProperties properties;

    public AnswerService(
            RetrievalService retrieval,
            Optional<ChatModel> chatModel,
            QueryRewriter rewriter,
            CitationVerifier verifier,
            ExtractiveAnswerer extractive,
            AppProperties properties) {
        this.retrieval = retrieval;
        this.chatModel = chatModel;
        this.rewriter = rewriter;
        this.verifier = verifier;
        this.extractive = extractive;
        this.properties = properties;
    }

    /**
     * @param history     earlier turns, oldest first; only used to rewrite a follow-up question
     * @param documentIds restrict retrieval to these documents, or empty
     * @param collection  restrict retrieval to this collection, or null
     * @param debug       include the full candidate ranking in the sources event
     */
    public record Question(String question, List<Turn> history, List<UUID> documentIds, String collection, boolean debug) {
    }

    public boolean generative() {
        return chatModel.isPresent();
    }

    /**
     * Checks the guardrails that must fail the request as a whole (before any event is sent) and
     * returns the question in canonical form: trimmed, history cut to the configured number of turns.
     *
     * @throws BadRequestException if the question is empty or too long
     */
    public Question validate(Question question) {
        String text = question.question() == null ? "" : question.question().strip();
        if (text.isEmpty()) {
            throw new BadRequestException("The question is empty.");
        }
        int maxChars = properties.answer().maxQuestionChars();
        if (text.length() > maxChars) {
            throw new BadRequestException(
                    "The question has %d characters; the limit is %d.".formatted(text.length(), maxChars));
        }
        List<Turn> history = new ArrayList<>();
        for (Turn turn : question.history() == null ? List.<Turn>of() : question.history()) {
            if (turn != null && turn.content() != null && !turn.content().isBlank()) {
                String content = turn.content().strip();
                history.add(new Turn(turn.role(), content.length() <= MAX_TURN_CHARS ? content : content.substring(0, MAX_TURN_CHARS)));
            }
        }
        int maxTurns = properties.answer().maxHistoryTurns();
        if (history.size() > maxTurns) {
            history = history.subList(history.size() - maxTurns, history.size());
        }
        return new Question(text, List.copyOf(history), question.documentIds(), question.collection(), question.debug());
    }

    /** Answers a validated question. Failures after this point are reported as a {@link Failure} event. */
    public void answer(Question question, Consumer<AnswerEvent> events) {
        List<Source> sources;
        Rewrite rewrite;
        boolean confident;
        try {
            rewrite = rewriter.rewrite(question.question(), question.history());
            RetrievalService.Result result = retrieval.retrieve(
                    rewrite.query(), new Filter(question.documentIds(), question.collection()));
            sources = number(result.selected());
            confident = result.confident() && !sources.isEmpty();
            events.accept(new Sources(
                    generative() ? GENERATIVE : EXTRACTIVE,
                    question.question(),
                    rewrite.query(),
                    rewrite.method(),
                    confident,
                    result.bestSimilarity(),
                    properties.retrieval().minSimilarity(),
                    sources,
                    question.debug() ? candidates(result) : null));
        } catch (ClientGoneException e) {
            return;
        } catch (RuntimeException e) {
            log.error("Retrieval failed", e);
            deliver(events, new Failure("Retrieval failed. See the server log for details."));
            return;
        }
        try {
            if (!confident) {
                // Guardrail: nothing in the corpus is close enough to the question. Saying so is cheaper
                // and safer than asking a model to answer from irrelevant passages.
                events.accept(new Token(PromptBuilder.NOT_FOUND));
                events.accept(new Done(generative() ? GENERATIVE : EXTRACTIVE, PromptBuilder.NOT_FOUND, true,
                        List.of(), List.of(), false, null, null, null));
            } else if (chatModel.isPresent()) {
                generate(chatModel.get(), rewrite.query(), sources, events);
            } else {
                extract(question.question(), sources, events);
            }
        } catch (ClientGoneException e) {
            log.debug("Client disconnected before the answer was complete");
        } catch (RuntimeException e) {
            log.error("Answer generation failed", e);
            deliver(events, new Failure("The answer could not be generated: " + e.getClass().getSimpleName() + "."));
        }
    }

    private void generate(ChatModel model, String question, List<Source> sources, Consumer<AnswerEvent> events) {
        StringBuilder answer = new StringBuilder();
        Usage[] usage = {null};
        String[] stopReason = {null};
        String[] modelId = {null};
        model.stream(PromptBuilder.build(question, sources))
                .doOnNext(response -> {
                    readMetadata(response, usage, stopReason, modelId);
                    String text = text(response);
                    if (text != null && !text.isEmpty()) {
                        answer.append(text);
                        events.accept(new Token(text));
                    }
                })
                .blockLast();
        if (answer.toString().isBlank()) {
            // A refusal or an answer cut off inside thinking produces no text; "not found" would be a lie.
            events.accept(new Failure("The model returned no answer (stop reason: %s).".formatted(stopReason[0])));
            return;
        }
        CitationVerifier.Verified verified = verifier.verify(answer.toString(), sources);
        events.accept(new Done(GENERATIVE, verified.answer(), verified.notFound(), verified.citations(),
                verified.invalid(), verified.uncited(), usage[0], stopReason[0],
                modelId[0] != null ? modelId[0] : properties.answer().model()));
    }

    private void extract(String question, List<Source> sources, Consumer<AnswerEvent> events) {
        ExtractiveAnswerer.Extract extract = extractive.answer(question, sources);
        for (int i = 0; i < extract.lines().size(); i++) {
            events.accept(new Token((i == 0 ? "" : "\n\n") + extract.lines().get(i)));
        }
        events.accept(new Done(EXTRACTIVE, extract.answer(), false, extract.citations(), List.of(), false, null, null, null));
    }

    private static List<Source> number(List<RetrievedChunk> chunks) {
        List<Source> sources = new ArrayList<>();
        for (RetrievedChunk chunk : chunks) {
            sources.add(Source.of(sources.size() + 1, chunk));
        }
        return sources;
    }

    private static List<Candidate> candidates(RetrievalService.Result result) {
        Map<Long, Integer> selectedAs = new HashMap<>();
        for (RetrievedChunk chunk : result.selected()) {
            selectedAs.put(chunk.chunkId(), selectedAs.size() + 1);
        }
        return result.candidates().stream()
                .map(chunk -> new Candidate(chunk.chunkId(), chunk.filename(), chunk.heading(), chunk.pageStart(),
                        chunk.pageEnd(), chunk.vectorRank(), chunk.keywordRank(), chunk.keywordScore(),
                        chunk.similarity(), chunk.score(), selectedAs.get(chunk.chunkId()), preview(chunk.content())))
                .toList();
    }

    private static String preview(String content) {
        String flat = content.replaceAll("\\s+", " ");
        return flat.length() <= PREVIEW_CHARS ? flat : flat.substring(0, PREVIEW_CHARS) + "...";
    }

    private static String text(ChatResponse response) {
        Generation generation = response.getResult();
        return generation == null || generation.getOutput() == null ? null : generation.getOutput().getText();
    }

    /** Usage and stop reason arrive on different stream events; keep the latest non-empty value of each. */
    private static void readMetadata(ChatResponse response, Usage[] usage, String[] stopReason, String[] modelId) {
        if (response.getMetadata() != null) {
            org.springframework.ai.chat.metadata.Usage reported = response.getMetadata().getUsage();
            if (reported != null && (positive(reported.getPromptTokens()) || positive(reported.getCompletionTokens()))) {
                usage[0] = new Usage(reported.getPromptTokens(), reported.getCompletionTokens());
            }
            if (response.getMetadata().getModel() != null && !response.getMetadata().getModel().isBlank()) {
                modelId[0] = response.getMetadata().getModel();
            }
        }
        Generation generation = response.getResult();
        if (generation != null && generation.getMetadata() != null && generation.getMetadata().getFinishReason() != null) {
            stopReason[0] = generation.getMetadata().getFinishReason();
        }
    }

    private static boolean positive(Integer value) {
        return value != null && value > 0;
    }

    private static void deliver(Consumer<AnswerEvent> events, AnswerEvent event) {
        try {
            events.accept(event);
        } catch (ClientGoneException e) {
            log.debug("Client disconnected before an error could be reported");
        }
    }

    /** Thrown by the event consumer when the client can no longer receive events. */
    public static class ClientGoneException extends RuntimeException {

        public ClientGoneException(Throwable cause) {
            super(cause);
        }
    }
}
