package pe.edu.nova.java.starters.idempotency;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Un cliente HTTP de verdad contra el Tomcat de la prueba. */
final class Http {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final HttpClient client = HttpClient.newHttpClient();
    private final int port;

    Http(int port) {
        this.port = port;
    }

    Call post(String path) {
        return new Call(path);
    }

    HttpResponse<String> get(String path) {
        return send(HttpRequest.newBuilder(uri(path)).GET().build());
    }

    static JsonNode json(HttpResponse<String> response) {
        return JSON.readTree(response.body());
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }

    private HttpResponse<String> send(HttpRequest request) {
        try {
            return client.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    /** Una petición POST en armado. */
    final class Call {

        private final String path;
        private final Map<String, String> headers = new LinkedHashMap<>();
        private String body = "{}";

        private Call(String path) {
            this.path = path;
            headers.put("Content-Type", "application/json");
        }

        Call key(String key) {
            return header(IdempotencyFilter.KEY_HEADER, key);
        }

        Call customer(String customer) {
            return header("X-Customer-Id", customer);
        }

        Call header(String name, String value) {
            headers.put(name, value);
            return this;
        }

        Call body(String json) {
            this.body = json;
            return this;
        }

        HttpResponse<String> send() {
            return Http.this.send(build());
        }

        CompletableFuture<HttpResponse<String>> sendAsync() {
            return client.sendAsync(build(), HttpResponse.BodyHandlers.ofString());
        }

        private HttpRequest build() {
            HttpRequest.Builder builder =
                    HttpRequest.newBuilder(uri(path)).POST(HttpRequest.BodyPublishers.ofString(body));
            headers.forEach(builder::header);
            return builder.build();
        }
    }
}
