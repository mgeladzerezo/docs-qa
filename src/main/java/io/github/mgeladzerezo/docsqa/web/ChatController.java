package io.github.mgeladzerezo.docsqa.web;

import io.github.mgeladzerezo.docsqa.answer.AnswerEvent;
import io.github.mgeladzerezo.docsqa.answer.AnswerService;
import io.github.mgeladzerezo.docsqa.answer.AnswerService.ClientGoneException;
import io.github.mgeladzerezo.docsqa.answer.AnswerService.Question;
import io.github.mgeladzerezo.docsqa.answer.QueryRewriter.Turn;
import io.github.mgeladzerezo.docsqa.config.AppProperties;
import io.github.mgeladzerezo.docsqa.embedding.EmbeddingGateway;
import io.github.mgeladzerezo.docsqa.retrieval.RetrievalService;
import io.github.mgeladzerezo.docsqa.retrieval.SearchRepository.Filter;
import io.github.mgeladzerezo.docsqa.retrieval.SearchRepository.Mode;
import java.io.IOException;
import java.util.List;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Question answering over Server-Sent Events. The stream is {@code sources}, any number of
 * {@code token}s, then one {@code done} (verified citations, usage) or one {@code error}.
 */
@RestController
public class ChatController {

    private final AnswerService answers;
    private final RetrievalService retrieval;
    private final EmbeddingGateway embeddings;
    private final AppProperties properties;

    public ChatController(
            AnswerService answers, RetrievalService retrieval, EmbeddingGateway embeddings, AppProperties properties) {
        this.answers = answers;
        this.retrieval = retrieval;
        this.embeddings = embeddings;
        this.properties = properties;
    }

    public record ChatRequest(
            String question, List<Turn> history, List<UUID> documentIds, String collection, Boolean debug) {
    }

    public record SearchRequest(String query, String mode, List<UUID> documentIds, String collection) {
    }

    public record Status(String mode, String chatModel, String embeddingModel, int embeddingDimension,
                         double minSimilarity, int maxQuestionChars) {
    }

    @GetMapping("/api/status")
    public Status status() {
        return new Status(
                answers.generative() ? AnswerService.GENERATIVE : AnswerService.EXTRACTIVE,
                answers.generative() ? properties.answer().model() : null,
                embeddings.modelName(),
                embeddings.dimension(),
                properties.retrieval().minSimilarity(),
                properties.answer().maxQuestionChars());
    }

    @PostMapping(value = "/api/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chat(@RequestBody ChatRequest request) {
        // Validation happens before the stream opens, so a bad request is an ordinary 400.
        Question question = answers.validate(new Question(
                request.question(), request.history(), request.documentIds(), request.collection(),
                Boolean.TRUE.equals(request.debug())));
        SseEmitter emitter = new SseEmitter(properties.answer().streamTimeout().toMillis());
        Thread.startVirtualThread(() -> {
            try {
                answers.answer(question, event -> send(emitter, event));
                emitter.complete();
            } catch (RuntimeException e) {
                emitter.completeWithError(e);
            }
        });
        return emitter;
    }

    /** The ranking of one retrieval mode, for inspection and for comparing modes by hand. */
    @PostMapping("/api/search")
    public RetrievalService.Result search(@RequestBody SearchRequest request) {
        if (request.query() == null || request.query().isBlank()
                || request.query().length() > properties.answer().maxQuestionChars()) {
            throw new ApiExceptionHandler.BadRequestException("The query is empty or too long.");
        }
        Mode mode = request.mode() == null ? Mode.HYBRID : Mode.valueOf(request.mode().toUpperCase(java.util.Locale.ROOT));
        return retrieval.retrieve(request.query().strip(), new Filter(request.documentIds(), request.collection()), mode);
    }

    private static void send(SseEmitter emitter, AnswerEvent event) {
        try {
            emitter.send(SseEmitter.event().name(event.name()).data(event, MediaType.APPLICATION_JSON));
        } catch (IOException | IllegalStateException e) {
            throw new ClientGoneException(e);
        }
    }
}
