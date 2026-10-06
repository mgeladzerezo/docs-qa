package io.github.mgeladzerezo.docsqa.config;

import com.anthropic.models.messages.OutputConfig;
import io.github.mgeladzerezo.docsqa.embedding.EmbeddingGateway;
import io.github.mgeladzerezo.docsqa.embedding.OnnxEmbeddingModelFactory;
import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.transformers.TransformersEmbeddingModel;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The two models, wired by hand instead of through Spring AI's starters because both need a decision
 * the starters do not make: the chat model must not exist at all without an API key (that is what
 * switches the service to extractive answers), and the embedding model must be replaceable.
 */
@Configuration
public class AiConfiguration {

    /**
     * The default, key-less embedding model. To use a hosted provider instead, set
     * {@code docsqa.embedding.provider} to anything but {@code onnx}, add that provider's Spring AI
     * module so an {@link EmbeddingModel} bean exists, and set the model name and dimension to match.
     */
    @Bean
    @ConditionalOnProperty(name = "docsqa.embedding.provider", havingValue = "onnx", matchIfMissing = true)
    TransformersEmbeddingModel onnxEmbeddingModel(AppProperties properties) {
        return OnnxEmbeddingModelFactory.create(properties.embedding());
    }

    @Bean
    EmbeddingGateway embeddingGateway(EmbeddingModel model, AppProperties properties) {
        return new EmbeddingGateway(
                model,
                properties.embedding().modelName(),
                properties.embedding().dimension(),
                properties.ingestion().maxConcurrentEmbeddingBatches());
    }

    /**
     * Claude through Spring AI's Anthropic module (which wraps the official Anthropic Java SDK).
     * Only created when a key is configured.
     *
     * <p>Request options follow the current Messages API for {@code claude-sonnet-5-5}: no
     * {@code temperature}/{@code top_p}/{@code top_k} (non-default sampling parameters are rejected),
     * no {@code thinking} block (omitting it runs adaptive thinking; it cannot be disabled), and depth
     * controlled through {@code output_config.effort}. {@code max_tokens} therefore has to cover
     * thinking as well as the visible answer.
     */
    @Bean
    @ConditionalOnExpression("!'${docsqa.anthropic.api-key:}'.isBlank()")
    ChatModel anthropicChatModel(AppProperties properties) {
        AnthropicChatOptions options = AnthropicChatOptions.builder()
                .apiKey(properties.anthropic().apiKey())
                .model(properties.answer().model())
                .maxTokens(properties.answer().maxTokens())
                .effort(OutputConfig.Effort.of(properties.answer().effort()))
                .build();
        return AnthropicChatModel.builder().options(options).build();
    }
}
