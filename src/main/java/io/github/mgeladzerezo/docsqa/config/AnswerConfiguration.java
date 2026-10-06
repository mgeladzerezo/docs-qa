package io.github.mgeladzerezo.docsqa.config;

import io.github.mgeladzerezo.docsqa.answer.AnswerService;
import io.github.mgeladzerezo.docsqa.answer.CitationVerifier;
import io.github.mgeladzerezo.docsqa.answer.ExtractiveAnswerer;
import io.github.mgeladzerezo.docsqa.answer.QueryRewriter;
import io.github.mgeladzerezo.docsqa.answer.SentenceRanker;
import io.github.mgeladzerezo.docsqa.document.DocumentRepository;
import io.github.mgeladzerezo.docsqa.embedding.EmbeddingGateway;
import io.github.mgeladzerezo.docsqa.retrieval.RetrievalService;
import java.util.Optional;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Assembles the answer stage. The chat model is optional everywhere: its absence is what selects
 * extractive answers and heuristic query rewriting.
 */
@Configuration
public class AnswerConfiguration {

    @Bean
    SentenceRanker sentenceRanker(EmbeddingGateway embeddings) {
        return new SentenceRanker(embeddings);
    }

    @Bean
    AnswerService answerService(
            RetrievalService retrieval,
            Optional<ChatModel> chatModel,
            SentenceRanker ranker,
            DocumentRepository documents,
            AppProperties properties) {
        CitationVerifier.PageLocator pages = documents::pageAt;
        return new AnswerService(
                retrieval,
                chatModel,
                new QueryRewriter(chatModel, properties.answer().maxQuestionChars()),
                new CitationVerifier(ranker, pages),
                new ExtractiveAnswerer(ranker, pages),
                properties);
    }
}
