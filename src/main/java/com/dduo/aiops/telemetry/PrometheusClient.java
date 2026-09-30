package com.dduo.aiops.telemetry;

import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Bounded read-only Prometheus HTTP client for server-owned queries. Callers never forward browser input as PromQL;
 * this class only enforces transport limits (timeout, response bytes, series count) and parses the result shape.
 */
@Component
public class PrometheusClient {

    static final int MAX_RESPONSE_BYTES = 512 * 1024;
    static final int MAX_SERIES = 50;
    static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(4);

    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final String baseUrl;

    @Autowired
    public PrometheusClient(ObjectMapper objectMapper, Environment environment) {
        this(objectMapper, environment.getProperty("aidiag.stage3.prometheus-base-url", "http://prometheus:9090"),
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build());
    }

    public PrometheusClient(ObjectMapper objectMapper, String baseUrl, HttpClient httpClient) {
        this.objectMapper = objectMapper;
        this.httpClient = httpClient;
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }

    public List<Series> range(String query, Instant start, Instant end, Duration step) throws QueryException {
        var results = execute("/api/v1/query_range?query=" + encode(query) + "&start=" + start.getEpochSecond()
                + "&end=" + end.getEpochSecond() + "&step=" + step.toSeconds());
        var series = new ArrayList<Series>();
        for (var result : results) {
            var points = new ArrayList<Point>();
            for (var value : result.path("values")) {
                var parsed = number(value.path(1).asText("NaN"));
                if (Double.isFinite(parsed)) {
                    points.add(new Point(value.path(0).asLong(), parsed));
                }
            }
            series.add(new Series(labels(result.path("metric")), List.copyOf(points)));
        }
        return List.copyOf(series);
    }

    public List<Sample> instant(String query, Instant time) throws QueryException {
        var results = execute("/api/v1/query?query=" + encode(query) + "&time=" + time.getEpochSecond());
        var samples = new ArrayList<Sample>();
        for (var result : results) {
            samples.add(new Sample(labels(result.path("metric")), number(result.path("value").path(1).asText("NaN"))));
        }
        return List.copyOf(samples);
    }

    private JsonNode execute(String pathAndQuery) throws QueryException {
        var request = HttpRequest.newBuilder(URI.create(baseUrl + pathAndQuery)).timeout(REQUEST_TIMEOUT).GET().build();
        try {
            var response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream body = response.body()) {
                var bytes = body.readNBytes(MAX_RESPONSE_BYTES + 1);
                if (bytes.length > MAX_RESPONSE_BYTES) {
                    throw new QueryException("RESPONSE_TOO_LARGE");
                }
                if (response.statusCode() != 200) {
                    throw new QueryException("QUERY_FAILED");
                }
                var parsed = objectMapper.readTree(bytes);
                var results = parsed.path("data").path("result");
                if (!"success".equals(parsed.path("status").asText()) || !results.isArray()) {
                    throw new QueryException("QUERY_FAILED");
                }
                if (results.size() > MAX_SERIES) {
                    throw new QueryException("TOO_MANY_SERIES");
                }
                return results;
            }
        } catch (QueryException exception) {
            throw exception;
        } catch (HttpTimeoutException exception) {
            throw new QueryException("QUERY_TIMEOUT");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new QueryException("INTERRUPTED");
        } catch (Exception exception) {
            throw new QueryException("QUERY_FAILED");
        }
    }

    private static String encode(String query) {
        return URLEncoder.encode(query, StandardCharsets.UTF_8);
    }

    private static Map<String, String> labels(JsonNode metric) {
        var labels = new TreeMap<String, String>();
        metric.fields().forEachRemaining(entry -> labels.put(entry.getKey(), entry.getValue().asText("")));
        return Map.copyOf(labels);
    }

    static double number(String text) {
        return switch (text) {
            case "+Inf", "Inf" -> Double.POSITIVE_INFINITY;
            case "-Inf" -> Double.NEGATIVE_INFINITY;
            default -> {
                try {
                    yield Double.parseDouble(text);
                } catch (NumberFormatException exception) {
                    yield Double.NaN;
                }
            }
        };
    }

    public record Point(long t, double v) { }

    public record Series(Map<String, String> labels, List<Point> points) { }

    public record Sample(Map<String, String> labels, double value) { }

    /** Carries a stable, browser-safe reason code; the Prometheus response body is never propagated. */
    public static final class QueryException extends Exception {
        private static final long serialVersionUID = 1L;
        private final String reason;

        public QueryException(String reason) {
            super(reason);
            this.reason = reason;
        }

        public String reason() {
            return reason;
        }
    }
}
