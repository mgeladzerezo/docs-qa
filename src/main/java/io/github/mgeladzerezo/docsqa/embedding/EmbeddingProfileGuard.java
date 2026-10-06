package io.github.mgeladzerezo.docsqa.embedding;

import io.github.mgeladzerezo.docsqa.document.DocumentRepository;
import io.github.mgeladzerezo.docsqa.document.DocumentRepository.EmbeddingProfile;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.stereotype.Component;

/**
 * Refuses to start the application when the configured embedding model does not match the data.
 * Vectors from two models live in unrelated spaces: comparing them yields numbers, not similarity,
 * and nothing downstream could detect it. So the mismatch is a startup error, checked three ways:
 * <ul>
 *   <li>the configured dimension equals the {@code vector(n)} column of the schema;</li>
 *   <li>the model really produces vectors of that dimension (one probe embedding, which also loads the
 *       model before the first request needs it);</li>
 *   <li>every document already in the library was embedded with the configured model name and dimension.</li>
 * </ul>
 * Ingestion and retrieval both depend on this bean, so neither runs before the checks have passed.
 */
@Component
@DependsOnDatabaseInitialization
public class EmbeddingProfileGuard {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingProfileGuard.class);

    public EmbeddingProfileGuard(EmbeddingGateway gateway, DocumentRepository documents) {
        verify(gateway, documents);
    }

    static void verify(EmbeddingGateway gateway, DocumentRepository documents) {
        int columnDimension = documents.embeddingColumnDimension();
        if (columnDimension != gateway.dimension()) {
            throw new IllegalStateException(
                    ("docsqa.embedding.dimension is %d but the chunk.embedding column is vector(%d). "
                            + "Changing the dimension needs a migration that recreates the column and its HNSW index.")
                            .formatted(gateway.dimension(), columnDimension));
        }
        gateway.embedQuery("dimension probe");
        List<EmbeddingProfile> foreign = documents.embeddingProfiles().stream()
                .filter(profile -> !profile.model().equals(gateway.modelName()) || profile.dimension() != gateway.dimension())
                .toList();
        if (!foreign.isEmpty()) {
            throw new IllegalStateException(
                    ("The library contains documents embedded with %s, but the configured embedding model is %s (%d). "
                            + "Vectors of different models cannot be mixed: restore the previous configuration, "
                            + "or delete those documents and ingest them again.")
                            .formatted(foreign, gateway.modelName(), gateway.dimension()));
        }
        log.info("Embedding model {} ({} dimensions) matches the schema and the stored documents",
                gateway.modelName(), gateway.dimension());
    }
}
