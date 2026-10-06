package io.github.mgeladzerezo.docsqa.web;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.mgeladzerezo.docsqa.document.DocumentService;
import io.github.mgeladzerezo.docsqa.support.FakeEmbeddingConfiguration;
import io.github.mgeladzerezo.docsqa.support.PostgresTest;
import io.github.mgeladzerezo.docsqa.support.SampleLibrary;
import io.github.mgeladzerezo.docsqa.support.SseClient;
import io.github.mgeladzerezo.docsqa.support.SseClient.Response;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * No API key, no chat model bean: the service must still answer, and say honestly that the answer is
 * a set of quotations rather than something a model wrote.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "docsqa.retrieval.min-similarity=0.12")
@Import(FakeEmbeddingConfiguration.class)
class ExtractiveChatFlowTest extends PostgresTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @LocalServerPort
    int port;

    @BeforeAll
    static void ingestSamples(@Autowired DocumentService documents, @Autowired JdbcClient jdbc) {
        jdbc.sql("TRUNCATE document CASCADE").update();
        SampleLibrary.ingest(documents, Duration.ofSeconds(90));
    }

    @Test
    void withoutAKeyTheAnswerIsLabelledExtractiveAndConsistsOfVerbatimQuotes() throws Exception {
        Response response = SseClient.post(port, "/api/chat", """
                {"question": "Why must I never use vinegar to descale the espresso machine?"}""");

        assertThat(response.only("sources").data().get("mode").asString()).isEqualTo("extractive");
        JsonNode done = response.only("done").data();
        assertThat(done.get("mode").asString()).isEqualTo("extractive");
        assertThat(done.get("usage").isNull()).isTrue();
        assertThat(done.get("model").isNull()).isTrue();
        JsonNode citations = done.get("citations");
        assertThat(citations).isNotEmpty();
        assertThat(done.get("answer").asString()).contains("vinegar");
        for (JsonNode citation : citations) {
            String quote = citation.get("quote").asString();
            assertThat(done.get("answer").asString()).contains("\"" + quote + "\" [" + citation.get("n").asInt() + "]");
            JsonNode page = JSON.readTree(get("/api/documents/" + citation.get("documentId").asString() + "/pages/" + citation.get("page").asInt()));
            int offset = citation.get("quoteStart").asInt() - page.get("charStart").asInt();
            assertThat(page.get("text").asString().substring(offset, offset + quote.length())).isEqualTo(quote);
        }
    }

    @Test
    void aQuestionOutsideTheCorpusIsStillNotFound() throws Exception {
        Response response = SseClient.post(port, "/api/chat", """
                {"question": "zxqv plorbnik wuggle frobnitz"}""");

        JsonNode done = response.only("done").data();
        assertThat(done.get("notFound").asBoolean()).isTrue();
        assertThat(done.get("citations")).isEmpty();
    }

    @Test
    void theStatusEndpointSaysExtractive() throws Exception {
        assertThat(JSON.readTree(get("/api/status")).get("mode").asString()).isEqualTo("extractive");
    }

    private String get(String path) throws Exception {
        HttpResponse<String> response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        return response.body();
    }
}
