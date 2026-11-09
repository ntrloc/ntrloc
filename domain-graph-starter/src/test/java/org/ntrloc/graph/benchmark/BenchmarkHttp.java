package org.ntrloc.graph.benchmark;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

// The benchmark talks to ntrloc only over HTTP, as a client would: seeding goes through the admin and
// mutation APIs, and timing measures whole requests. Depending on nothing but the wire format keeps the
// benchmark valid across internal refactors -- which is the point of a before/after comparison.
final class BenchmarkHttp {

    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final String baseUrl;
    private final JsonMapper json;

    BenchmarkHttp(String baseUrl, JsonMapper json) {
        this.baseUrl = baseUrl;
        this.json = json;
    }

    JsonNode get(String token, String path) {
        return parse(send(token, "GET", path, null));
    }

    JsonNode post(String token, String path, Object body) {
        return parse(send(token, "POST", path, body));
    }

    JsonNode put(String token, String path, Object body) {
        return parse(send(token, "PUT", path, body));
    }

    // A list serialized as List<elementType>, so each element carries the type id its declared
    // (polymorphic) element type needs -- a plain List would be written as List<Object>, without it.
    <T> JsonNode postList(String token, String path, List<? extends T> list, Class<T> elementType) {
        String body = json.writerFor(json.getTypeFactory().constructCollectionType(List.class, elementType)).writeValueAsString(list);
        return parse(send(token, "POST", path, new RawJson(body)));
    }

    private record RawJson(String body) {}

    // Returns the response body; fails on anything but 2xx, so a broken request can't be timed as a
    // fast one.
    String send(String token, String method, String path, Object body) {
        var builder = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .timeout(Duration.ofMinutes(5))
                .header("Authorization", "Bearer " + token)
                .header("Accept", "application/json");
        if (body == null) {
            builder.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            builder.header("Content-Type", "application/json")
                    .method(method, HttpRequest.BodyPublishers.ofString(
                            body instanceof RawJson raw ? raw.body() : json.writeValueAsString(body)));
        }
        try {
            HttpResponse<String> response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                throw new IllegalStateException(method + " " + path + " -> " + response.statusCode() + ": " + response.body());
            }
            return response.body();
        } catch (IOException e) {
            throw new IllegalStateException(method + " " + path + " failed", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(method + " " + path + " interrupted", e);
        }
    }

    JsonNode parse(String body) {
        return body == null || body.isBlank() ? json.createObjectNode() : json.readTree(body);
    }
}
