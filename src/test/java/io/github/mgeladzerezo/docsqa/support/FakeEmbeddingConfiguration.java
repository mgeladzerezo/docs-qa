package io.github.mgeladzerezo.docsqa.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/** Supplies the deterministic embedding model that the {@code test} profile expects instead of ONNX. */
@TestConfiguration(proxyBeanMethods = false)
public class FakeEmbeddingConfiguration {

    @Bean
    HashingEmbeddingModel hashingEmbeddingModel() {
        return new HashingEmbeddingModel();
    }
}
