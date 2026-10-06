package io.github.mgeladzerezo.docsqa.sample;

import io.github.mgeladzerezo.docsqa.config.AppProperties;
import io.github.mgeladzerezo.docsqa.document.DocumentRepository;
import io.github.mgeladzerezo.docsqa.document.DocumentService;
import java.io.ByteArrayInputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * On the first start with an empty library, renders the bundled sample documents to PDF and uploads
 * them through the normal upload path, so the demo has something to answer questions about and the
 * samples are ingested exactly like user files.
 */
@Component
public class SampleCorpusLoader implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SampleCorpusLoader.class);

    private final AppProperties properties;
    private final DocumentRepository documents;
    private final DocumentService service;

    public SampleCorpusLoader(AppProperties properties, DocumentRepository documents, DocumentService service) {
        this.properties = properties;
        this.documents = documents;
        this.service = service;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!properties.sampleCorpus().preload() || documents.count() > 0) {
            return;
        }
        for (CorpusDocument source : CorpusDocument.loadBundled()) {
            byte[] pdf = CorpusPdfRenderer.render(source);
            service.upload(source.fileName(), new ByteArrayInputStream(pdf), "samples", true);
        }
        log.info("Queued {} sample documents for ingestion", CorpusDocument.BUNDLED.size());
    }
}
