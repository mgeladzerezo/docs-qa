package io.github.mgeladzerezo.docsqa.web;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.mgeladzerezo.docsqa.document.DocumentService;
import io.github.mgeladzerezo.docsqa.support.FakeEmbeddingConfiguration;
import io.github.mgeladzerezo.docsqa.support.PostgresTest;
import io.github.mgeladzerezo.docsqa.support.SampleLibrary;
import io.github.mgeladzerezo.docsqa.support.ScriptedChatModel;
import io.github.mgeladzerezo.docsqa.support.SseClient;
import io.github.mgeladzerezo.docsqa.support.SseClient.Response;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The whole question-answering flow over HTTP: real PDFs, real pgvector, the real SSE endpoint, and a
 * scripted chat model that says what the test wants it to say, including citations that do not exist.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"docsqa.retrieval.min-similarity=0.12", "docsqa.answer.max-question-chars=200"})
@Import({FakeEmbeddingConfiguration.class, ChatFlowTest.ScriptedModelConfiguration.class})
class ChatFlowTest extends PostgresTest {

    private static final Pattern SOURCE = Pattern.compile("<source id=\"(\\d+)\"[^>]*>(.*?)</source>", Pattern.DOTALL);
    private static final ObjectMapper JSON = new ObjectMapper();
    static final AtomicBoolean FAIL_ANSWER = new AtomicBoolean();

    @TestConfiguration(proxyBeanMethods = false)
    static class ScriptedModelConfiguration {

        @Bean
        ScriptedChatModel scriptedChatModel() {
            return new ScriptedChatModel(ChatFlowTest::script);
        }
    }

    /** Rewrites follow-ups, otherwise cites the source that talks about citric acid, plus one source that does not exist. */
    private static String script(Prompt prompt) {
        if (prompt.getSystemMessage().getText().contains("Rewrite the last question")) {
            return "How long does the descaling programme of the Aurora X2 espresso machine take?";
        }
        if (FAIL_ANSWER.get()) {
            throw new IllegalStateException("the provider is down");
        }
        Matcher matcher = SOURCE.matcher(prompt.getUserMessage().getText());
        int citric = 0;
        while (matcher.find()) {
            if (citric == 0 && matcher.group(2).contains("citric acid")) {
                citric = Integer.parseInt(matcher.group(1));
            }
        }
        if (citric == 0) {
            return "Not found in the documents.";
        }
        return "Descale the machine with citric acid, never with vinegar [" + citric + "]. The warranty is five years [9].";
    }

    @Autowired
    DocumentService documents;
    @Autowired
    ScriptedChatModel model;
    @Autowired
    JdbcClient jdbc;
    @LocalServerPort
    int port;

    static Map<String, UUID> ids;

    @BeforeAll
    static void ingestSamples(@Autowired DocumentService documents, @Autowired JdbcClient jdbc) {
        jdbc.sql("TRUNCATE document CASCADE").update();
        ids = SampleLibrary.ingest(documents, Duration.ofSeconds(90));
    }

    @Test
    void theStreamIsSourcesThenTokensThenOneDoneWithVerifiedCitations() throws Exception {
        FAIL_ANSWER.set(false);

        Response response = SseClient.post(port, "/api/chat", """
                {"question": "How do I descale the Aurora X2 espresso machine?"}""");

        assertThat(response.status()).isEqualTo(200);
        assertThat(response.contentType()).startsWith("text/event-stream");
        assertThat(response.names().getFirst()).isEqualTo("sources");
        assertThat(response.names().getLast()).isEqualTo("done");
        assertThat(response.names().subList(1, response.names().size() - 1)).isNotEmpty().containsOnly("token");
        assertThat(response.named("token")).hasSizeGreaterThan(3).as("the answer is streamed in pieces");

        JsonNode sources = response.only("sources").data();
        assertThat(sources.get("mode").asString()).isEqualTo("generative");
        assertThat(sources.get("confident").asBoolean()).isTrue();
        assertThat(sources.get("candidates").isNull()).as("candidates only when debug is on").isTrue();
        JsonNode first = sources.get("sources").get(0);
        assertThat(first.get("filename").asString()).isEqualTo("aurora-x2-espresso-manual.pdf");

        JsonNode done = response.only("done").data();
        assertThat(response.streamedText()).contains("[9]").as("tokens are unverified text").isNotBlank();
        assertThat(done.get("answer").asString()).doesNotContain("[9]").contains("citric acid");
        assertThat(done.get("invalidCitations")).extracting(JsonNode::asInt).containsExactly(9);
        assertThat(done.get("notFound").asBoolean()).isFalse();
        assertThat(done.get("usage").get("inputTokens").asInt()).isEqualTo(321);
        assertThat(done.get("usage").get("outputTokens").asInt()).isEqualTo(45);
        assertThat(done.get("stopReason").asString()).isEqualTo("end_turn");
        assertThat(done.get("model").asString()).isEqualTo("scripted-model");

        JsonNode citation = done.get("citations").get(0);
        assertThat(done.get("citations")).hasSize(1);
        assertThat(citation.get("quote").asString()).containsIgnoringCase("citric acid");
        assertQuoteIsOnItsPage(citation);
    }

    @Test
    void aQuestionTheCorpusDoesNotCoverIsAnsweredNotFoundWithoutAskingTheModel() throws Exception {
        int promptsBefore = model.prompts().size();

        Response response = SseClient.post(port, "/api/chat", """
                {"question": "zxqv plorbnik wuggle frobnitz"}""");

        assertThat(response.only("sources").data().get("confident").asBoolean()).isFalse();
        JsonNode done = response.only("done").data();
        assertThat(done.get("notFound").asBoolean()).isTrue();
        assertThat(done.get("answer").asString()).isEqualTo("Not found in the documents.");
        assertThat(done.get("citations")).isEmpty();
        assertThat(model.prompts()).hasSize(promptsBefore);
    }

    @Test
    void aFollowUpIsRewrittenWithTheHistoryBeforeRetrieval() throws Exception {
        FAIL_ANSWER.set(false);

        Response response = SseClient.post(port, "/api/chat", """
                {"question": "How long does it take?",
                 "history": [{"role": "user", "content": "How do I descale the Aurora X2?"},
                             {"role": "assistant", "content": "Use citric acid [1]."}]}""");

        JsonNode sources = response.only("sources").data();
        assertThat(sources.get("rewrite").asString()).isEqualTo("model");
        assertThat(sources.get("question").asString()).isEqualTo("How long does it take?");
        assertThat(sources.get("standaloneQuestion").asString()).contains("descaling programme");
        assertThat(sources.get("sources").get(0).get("filename").asString()).isEqualTo("aurora-x2-espresso-manual.pdf");
    }

    @Test
    void theDocumentFilterRestrictsWhatCanBeCited() throws Exception {
        UUID travel = ids.get("northwind-travel-policy.pdf");

        Response response = SseClient.post(port, "/api/chat", """
                {"question": "How do I descale the espresso machine?", "documentIds": ["%s"], "debug": true}""".formatted(travel));

        JsonNode sources = response.only("sources").data();
        for (JsonNode source : sources.get("sources")) {
            assertThat(source.get("documentId").asString()).isEqualTo(travel.toString());
        }
        assertThat(sources.get("candidates")).as("debug adds the ranking").isNotEmpty();
    }

    @Test
    void theDebugViewShowsVectorRankKeywordRankAndFusedScoreOfEveryCandidate() throws Exception {
        Response response = SseClient.post(port, "/api/chat", """
                {"question": "How do I descale the Aurora X2 espresso machine?", "debug": true}""");

        JsonNode candidates = response.only("sources").data().get("candidates");
        assertThat(candidates.size()).isGreaterThan(3);
        JsonNode top = candidates.get(0);
        assertThat(top.get("score").asDouble()).isGreaterThan(0);
        assertThat(top.get("vectorRank").isNull() && top.get("keywordRank").isNull()).isFalse();
        double previous = Double.MAX_VALUE;
        for (JsonNode candidate : candidates) {
            assertThat(candidate.get("score").asDouble()).isLessThanOrEqualTo(previous);
            previous = candidate.get("score").asDouble();
        }
        assertThat(candidates).anyMatch(candidate -> candidate.get("selectedAs").isNumber());
    }

    @Test
    void aFailingModelEndsTheStreamWithAnErrorEventNotAHang() throws Exception {
        FAIL_ANSWER.set(true);
        try {
            Response response = SseClient.post(port, "/api/chat", """
                    {"question": "How do I descale the Aurora X2 espresso machine?"}""");

            assertThat(response.names()).containsExactly("sources", "error");
            assertThat(response.only("error").data().get("message").asString()).contains("IllegalStateException");
        } finally {
            FAIL_ANSWER.set(false);
        }
    }

    @Test
    void emptyAndOversizedQuestionsAreRejectedBeforeTheStreamOpens() throws Exception {
        Response empty = SseClient.post(port, "/api/chat", "{\"question\": \"   \"}");
        Response tooLong = SseClient.post(port, "/api/chat", "{\"question\": \"" + "a".repeat(201) + "\"}");

        assertThat(empty.status()).isEqualTo(400);
        assertThat(tooLong.status()).isEqualTo(400);
        assertThat(tooLong.body()).contains("201").contains("200");
        assertThat(tooLong.events()).isEmpty();
    }

    @Test
    void theStatusEndpointReportsTheMode() throws Exception {
        JsonNode status = JSON.readTree(get("/api/status"));

        assertThat(status.get("mode").asString()).isEqualTo("generative");
        assertThat(status.get("embeddingModel").asString()).isEqualTo("test-hashing-384");
    }

    private void assertQuoteIsOnItsPage(JsonNode citation) throws Exception {
        String documentId = citation.get("documentId").asString();
        int page = citation.get("page").asInt();
        JsonNode pageView = JSON.readTree(get("/api/documents/" + documentId + "/pages/" + page));
        int offset = citation.get("quoteStart").asInt() - pageView.get("charStart").asInt();
        String quote = citation.get("quote").asString();
        assertThat(pageView.get("text").asString().substring(offset, offset + quote.length())).isEqualTo(quote);
    }

    private String get(String path) throws Exception {
        HttpResponse<String> response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        return response.body();
    }
}
