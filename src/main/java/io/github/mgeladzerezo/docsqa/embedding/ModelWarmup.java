package io.github.mgeladzerezo.docsqa.embedding;

import io.github.mgeladzerezo.docsqa.config.AppProperties;
import java.util.List;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.ai.transformers.TransformersEmbeddingModel;

/**
 * Downloads the ONNX model, the tokenizer and DJL's native libraries into the configured cache and
 * embeds one sentence to prove they load. The Docker build runs it once so the image starts without
 * network access and without a multi-minute first-run download.
 *
 * <p>Run as {@code java -Dloader.main=...ModelWarmup -cp app.jar org.springframework.boot.loader.launch.PropertiesLauncher};
 * the cache directory comes from {@code DOCSQA_MODEL_CACHE_DIR} exactly as it does for the service.
 */
public final class ModelWarmup {

    private ModelWarmup() {
    }

    public static void main(String[] args) throws Exception {
        StandardEnvironment environment = new StandardEnvironment();
        List<PropertySource<?>> yaml = new YamlPropertySourceLoader()
                .load("application", new ClassPathResource("application.yml"));
        yaml.forEach(environment.getPropertySources()::addLast);
        AppProperties properties = Binder.get(environment).bind("docsqa", AppProperties.class).get();

        TransformersEmbeddingModel model = OnnxEmbeddingModelFactory.create(properties.embedding());
        model.afterPropertiesSet();
        float[] vector = model.embed("warm-up sentence");
        if (vector.length != properties.embedding().dimension()) {
            throw new IllegalStateException("Model produced %d dimensions, expected %d"
                    .formatted(vector.length, properties.embedding().dimension()));
        }
        System.out.println("Model ready in " + properties.embedding().cacheDir().toAbsolutePath());
    }
}
