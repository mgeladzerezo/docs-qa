package io.github.mgeladzerezo.docsqa.embedding;

import io.github.mgeladzerezo.docsqa.config.AppProperties;
import java.nio.file.Path;
import java.util.Map;
import org.springframework.ai.transformers.TransformersEmbeddingModel;

/**
 * Builds Spring AI's in-JVM ONNX embedding model (all-MiniLM-L6-v2 by default) so that it works
 * without an API key and without surprises.
 *
 * <p>What Spring AI 2.0.1 actually does, found by reading the jar and running it:
 * <ul>
 *   <li>The {@code model.onnx} inside {@code spring-ai-transformers.jar} is a 133-byte Git LFS pointer,
 *       not the model. The real file (about 87 MB) and the tokenizer are downloaded from GitHub on
 *       first use and cached by URL under the resource cache directory.</li>
 *   <li>Mean pooling runs on DJL's PyTorch engine, which downloads its native libraries (about 260 MB
 *       on Windows) into {@code DJL_CACHE_DIR} on first use.</li>
 *   <li>The bundled tokenizer configuration truncates input at 128 word pieces. Text beyond that is
 *       silently ignored, which would make the tail of every larger chunk invisible to vector search.
 *       The limit is raised to the model's 256 here, and the chunk size is chosen to stay under it.</li>
 * </ul>
 * Both caches are put under one configurable directory so they can live on a volume or be baked into
 * the Docker image.
 */
public final class OnnxEmbeddingModelFactory {

    private static final String DJL_CACHE_PROPERTY = "DJL_CACHE_DIR";

    private OnnxEmbeddingModelFactory() {
    }

    /** Returns a configured model; the caller (or Spring) must invoke {@code afterPropertiesSet()}. */
    public static TransformersEmbeddingModel create(AppProperties.Embedding properties) {
        Path cacheDir = properties.cacheDir().toAbsolutePath().normalize();
        // DJL reads the environment variable first and this system property second.
        if (System.getenv(DJL_CACHE_PROPERTY) == null && System.getProperty(DJL_CACHE_PROPERTY) == null) {
            System.setProperty(DJL_CACHE_PROPERTY, cacheDir.resolve("djl").toString());
        }
        TransformersEmbeddingModel model = new TransformersEmbeddingModel();
        model.setModelResource(properties.modelUri());
        model.setTokenizerResource(properties.tokenizerUri());
        model.setResourceCacheDirectory(cacheDir.resolve("onnx").toString());
        model.setTokenizerOptions(Map.of(
                "maxLength", String.valueOf(properties.maxInputTokens()),
                "truncation", "true",
                "padding", "true"));
        return model;
    }
}
