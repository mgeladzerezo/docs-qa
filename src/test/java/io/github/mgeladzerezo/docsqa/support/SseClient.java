package io.github.mgeladzerezo.docsqa.support;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Posts a JSON body and reads the Server-Sent Events of the response the way a browser would. */
public final class SseClient {

    /** One parsed event. */
    public record Event(String name, JsonNode data) {
    }

    public record Response(int status, String contentType, List<Event> events, String body) {

        public List<Event> named(String name) {
            return events.stream().filter(event -> event.name().equals(name)).toList();
        }

        public Event only(String name) {
            List<Event> matching = named(name);
            if (matching.size() != 1) {
                throw new AssertionError("Expected exactly one '%s' event but got %d in %s".formatted(name, matching.size(), names()));
            }
            return matching.getFirst();
        }

        public List<String> names() {
            return events.stream().map(Event::name).toList();
        }

        public String streamedText() {
            StringBuilder text = new StringBuilder();
            named("token").forEach(event -> text.append(event.data().get("text").asString()));
            return text.toString();
        }
    }

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient CLIENT = HttpClient.newHttpClient();

    private SseClient() {
    }

    public static Response post(int port, String path, String json) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream")
                .timeout(Duration.ofSeconds(60))
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();
        HttpResponse<java.io.InputStream> response = CLIENT.send(request, HttpResponse.BodyHandlers.ofInputStream());
        String contentType = response.headers().firstValue("Content-Type").orElse("");
        List<Event> events = new ArrayList<>();
        StringBuilder body = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
            String name = null;
            StringBuilder data = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                body.append(line).append('\n');
                if (line.isEmpty()) {
                    if (name != null) {
                        events.add(new Event(name, JSON.readTree(data.toString())));
                    }
                    name = null;
                    data.setLength(0);
                } else if (line.startsWith("event:")) {
                    name = line.substring(6).strip();
                } else if (line.startsWith("data:")) {
                    data.append(line.substring(5).strip());
                }
            }
        }
        return new Response(response.statusCode(), contentType, events, body.toString());
    }
}
