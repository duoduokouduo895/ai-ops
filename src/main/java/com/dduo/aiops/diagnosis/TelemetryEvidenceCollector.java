package com.dduo.aiops.diagnosis;

import java.io.InputStream;
import java.math.BigDecimal;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/** Executes bounded, server-owned observability queries and stores only compact evidence summaries. */
@Component
public class TelemetryEvidenceCollector {

    static final int MAX_RESPONSE_BYTES = 128 * 1024;
    static final int MAX_TRACES = 50;

    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final String prometheusBaseUrl;
    private final String lokiBaseUrl;
    private final String tempoBaseUrl;
    private final Duration timeout;

    public TelemetryEvidenceCollector(ObjectMapper objectMapper, Environment environment) {
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
        this.prometheusBaseUrl = baseUrl(environment, "aidiag.stage3.prometheus-base-url", "http://prometheus:9090");
        this.lokiBaseUrl = baseUrl(environment, "aidiag.stage3.loki-base-url", "http://loki:3100");
        this.tempoBaseUrl = baseUrl(environment, "aidiag.stage3.tempo-base-url", "http://tempo:3200");
        var timeoutSeconds = environment.getProperty("aidiag.stage3.tool-timeout-seconds", Integer.class, 8);
        if (timeoutSeconds <= 0 || timeoutSeconds > 10) {
            throw new IllegalArgumentException("aidiag.stage3.tool-timeout-seconds must be between 1 and 10");
        }
        this.timeout = Duration.ofSeconds(timeoutSeconds);
    }

    public CollectedObservation collectMetric(DiagnosisRule rule, TimeWindow window) {
        var request = json(Map.of("query", rule.metricQuery(), "start", window.start().toString(),
                "end", window.end().toString(), "step", "30s"));
        var started = System.nanoTime();
        try {
            var response = get(prometheusBaseUrl + "/api/v1/query_range", Map.of("query", rule.metricQuery(), "start",
                    Long.toString(window.start().getEpochSecond()), "end", Long.toString(window.end().getEpochSecond()), "step", "30"), true);
            var maximum = maximumSample(response.path("data").path("result"));
            var matched = maximum.sampleCount() > 0 && maximum.value() >= rule.metricThreshold();
            var rawData = json(Map.of("sampleCount", maximum.sampleCount(), "maximum", maximum.value(),
                    "threshold", rule.metricThreshold()));
            var evidence = new DiagnosisRepository.EvidenceDraft("METRIC", "prometheus", window.start(), window.end(), request,
                    "固定 PromQL（" + rule.metricDescription() + "）最大值 " + maximum.value() + "，阈值 " + rule.metricThreshold(),
                    rawData, BigDecimal.valueOf(30), matched);
            return success(matched, "queryServiceMetrics", request, rawData, started, evidence);
        } catch (Exception exception) {
            return failed("queryServiceMetrics", request, started);
        }
    }

    public CollectedObservation collectLogs(DiagnosisRule rule, TimeWindow window) {
        var request = json(Map.of("query", rule.logQuery(), "start", window.start().toString(), "end", window.end().toString(),
                "limit", 200));
        var started = System.nanoTime();
        try {
            var response = get(lokiBaseUrl + "/loki/api/v1/query_range", Map.of("query", rule.logQuery(), "start",
                    Long.toString(window.start().toEpochMilli() * 1_000_000), "end",
                    Long.toString(window.end().toEpochMilli() * 1_000_000), "limit", "200", "direction", "forward"), true);
            var matchedLines = logLineCount(response.path("data").path("result"));
            var matched = matchedLines > 0;
            var rawData = json(Map.of("matchedLines", matchedLines, "marker", rule.logMarker()));
            var evidence = new DiagnosisRepository.EvidenceDraft("LOG", "loki", window.start(), window.end(), request,
                    "固定 LogQL 匹配到 " + matchedLines + " 条 " + rule.logMarker() + " 事件", rawData,
                    BigDecimal.valueOf(25), matched);
            return success(matched, "queryServiceLogs", request, rawData, started, evidence);
        } catch (Exception exception) {
            return failed("queryServiceLogs", request, started);
        }
    }

    public CollectedObservation collectTraces(DiagnosisRule rule, TimeWindow window) {
        var request = json(Map.of("query", rule.traceQuery(), "start", window.start().toString(), "end", window.end().toString(),
                "limit", MAX_TRACES));
        var started = System.nanoTime();
        try {
            var response = get(tempoBaseUrl + "/api/search", Map.of("q", rule.traceQuery(), "start",
                    Long.toString(window.start().getEpochSecond()), "end", Long.toString(window.end().getEpochSecond()),
                    "limit", Integer.toString(MAX_TRACES)), false);
            var traces = compactTraces(response.path("traces"));
            var matched = !traces.isEmpty();
            var rawData = json(Map.of("traceCount", traces.size(), "traces", traces));
            var evidence = new DiagnosisRepository.EvidenceDraft("TRACE", "tempo", window.start(), window.end(), request,
                    "固定 TraceQL 匹配到 " + traces.size() + " 条 Trace（最多保留 " + MAX_TRACES + " 条摘要）", rawData,
                    BigDecimal.valueOf(20), matched);
            return success(matched, "queryServiceTraces", request, rawData, started, evidence);
        } catch (Exception exception) {
            return failed("queryServiceTraces", request, started);
        }
    }

    private CollectedObservation success(boolean matched, String toolName, String request, String rawData, long started,
            DiagnosisRepository.EvidenceDraft evidence) {
        return new CollectedObservation(matched, true, new DiagnosisRepository.ToolObservation(toolName, request,
                rawData, "SUCCEEDED", elapsedMillis(started), null, evidence));
    }

    private CollectedObservation failed(String toolName, String request, long started) {
        return new CollectedObservation(false, false, new DiagnosisRepository.ToolObservation(toolName, request,
                "{\"outcome\":\"collection_failed\"}", "FAILED", elapsedMillis(started), "collection_failed", null));
    }

    private JsonNode get(String endpoint, Map<String, String> parameters, boolean requireSuccessStatus) throws Exception {
        var query = parameters.entrySet().stream().map(entry -> encode(entry.getKey()) + "=" + encode(entry.getValue()))
                .reduce((left, right) -> left + "&" + right).orElse("");
        var request = HttpRequest.newBuilder(URI.create(endpoint + "?" + query)).timeout(timeout).GET().build();
        var response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
        try (InputStream body = response.body()) {
            var bytes = body.readNBytes(MAX_RESPONSE_BYTES + 1);
            if (response.statusCode() != 200 || bytes.length > MAX_RESPONSE_BYTES) {
                throw new IllegalStateException("telemetry query failed");
            }
            var parsed = objectMapper.readTree(bytes);
            if (requireSuccessStatus && !"success".equals(parsed.path("status").asText())) {
                throw new IllegalStateException("telemetry query returned an error");
            }
            return parsed;
        }
    }

    private static List<Map<String, Object>> compactTraces(JsonNode input) {
        var result = new ArrayList<Map<String, Object>>();
        for (JsonNode trace : input) {
            if (result.size() == MAX_TRACES) {
                break;
            }
            var traceId = trace.path("traceID").asText("");
            if (!traceId.matches("[0-9a-fA-F]{32}")) {
                continue;
            }
            var item = new LinkedHashMap<String, Object>();
            item.put("traceId", traceId.toLowerCase(java.util.Locale.ROOT));
            item.put("durationMs", Math.max(0, trace.path("durationMs").asLong(0)));
            result.add(item);
        }
        return result;
    }

    private static Maximum maximumSample(JsonNode results) {
        var maximum = 0D;
        var count = 0;
        for (JsonNode series : results) {
            for (JsonNode sample : series.path("values")) {
                try {
                    var value = Double.parseDouble(sample.path(1).asText());
                    if (Double.isFinite(value)) {
                        maximum = Math.max(maximum, value);
                        count++;
                    }
                } catch (NumberFormatException ignored) {
                    // Invalid and non-finite samples are not evidence.
                }
            }
        }
        return new Maximum(maximum, count);
    }

    private static int logLineCount(JsonNode results) {
        var count = 0;
        for (JsonNode stream : results) {
            count = Math.min(200, count + stream.path("values").size());
        }
        return count;
    }

    private static int elapsedMillis(long started) {
        return (int) Math.min(10_000, Duration.ofNanos(System.nanoTime() - started).toMillis());
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Cannot serialize controlled telemetry query", exception);
        }
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String baseUrl(Environment environment, String property, String fallback) {
        var value = environment.getProperty(property, fallback);
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    public record TimeWindow(Instant start, Instant end) {
    }

    public record CollectedObservation(boolean matched, boolean collected,
            DiagnosisRepository.ToolObservation observation) {
    }

    private record Maximum(double value, int sampleCount) {
    }
}
