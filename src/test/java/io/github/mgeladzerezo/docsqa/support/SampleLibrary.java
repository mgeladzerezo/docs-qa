package io.github.mgeladzerezo.docsqa.support;

import static org.awaitility.Awaitility.await;

import io.github.mgeladzerezo.docsqa.document.DocumentRecord;
import io.github.mgeladzerezo.docsqa.document.DocumentService;
import io.github.mgeladzerezo.docsqa.document.DocumentStatus;
import io.github.mgeladzerezo.docsqa.sample.CorpusDocument;
import io.github.mgeladzerezo.docsqa.sample.CorpusPdfRenderer;
import java.io.ByteArrayInputStream;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Uploads the bundled sample PDFs through the normal path and waits until every one is searchable. */
public final class SampleLibrary {

    private SampleLibrary() {
    }

    /** @return document id by file name */
    public static Map<String, UUID> ingest(DocumentService service, Duration timeout) {
        Map<String, UUID> ids = new LinkedHashMap<>();
        for (CorpusDocument source : CorpusDocument.loadBundled()) {
            byte[] pdf = CorpusPdfRenderer.render(source);
            ids.put(source.fileName(), service.upload(source.fileName(), new ByteArrayInputStream(pdf), "samples", true).document().id());
        }
        await().atMost(timeout).pollInterval(Duration.ofMillis(200)).until(() -> ids.values().stream()
                .map(service::find)
                .allMatch(document -> document.map(DocumentRecord::status).orElse(null) == DocumentStatus.READY));
        return ids;
    }
}
