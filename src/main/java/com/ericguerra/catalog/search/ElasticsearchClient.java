package com.ericguerra.catalog.search;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/** Thin JSON-over-HTTP client for the handful of Elasticsearch endpoints the catalog needs. */
@Component
public class ElasticsearchClient {
    public static class EsException extends RuntimeException {
        private final int status;

        public EsException(int status, String message) {
            super(message);
            this.status = status;
        }

        public int status() { return status; }
    }

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private final String baseUrl;
    private final ObjectMapper json;

    public ElasticsearchClient(@Value("${catalog.elasticsearch.url:http://localhost:9200}") String baseUrl, ObjectMapper json) {
        this.baseUrl = baseUrl.replaceAll("/$", "");
        this.json = json;
    }

    public JsonNode request(String method, String path, Object body) {
        return requestRaw(method, path, body == null ? null : write(body), "application/json");
    }

    public JsonNode bulk(String ndjson) {
        return requestRaw("POST", "/_bulk", ndjson, "application/x-ndjson");
    }

    public boolean exists(String path) {
        try {
            HttpResponse<Void> r = http.send(HttpRequest.newBuilder(URI.create(baseUrl + path)).method("HEAD", HttpRequest.BodyPublishers.noBody()).build(),
                    HttpResponse.BodyHandlers.discarding());
            return r.statusCode() == 200;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private JsonNode requestRaw(String method, String path, String body, String contentType) {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(baseUrl + path)).timeout(Duration.ofSeconds(10))
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        if (body != null) b.header("Content-Type", contentType);
        try {
            HttpResponse<String> r = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() >= 400) throw new EsException(r.statusCode(), method + " " + path + " -> " + r.statusCode() + ": " + truncate(r.body()));
            return r.body().isEmpty() ? json.createObjectNode() : json.readTree(r.body());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    public String write(Object o) {
        try {
            return o instanceof String s ? s : json.writeValueAsString(o);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String truncate(String s) {
        return s.length() > 400 ? s.substring(0, 400) + "…" : s;
    }
}
