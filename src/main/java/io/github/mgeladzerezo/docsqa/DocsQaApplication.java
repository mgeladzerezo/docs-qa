package io.github.mgeladzerezo.docsqa;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/** Entry point of the document question-answering service. */
@SpringBootApplication
@ConfigurationPropertiesScan
public class DocsQaApplication {

    public static void main(String[] args) {
        SpringApplication.run(DocsQaApplication.class, args);
    }
}
