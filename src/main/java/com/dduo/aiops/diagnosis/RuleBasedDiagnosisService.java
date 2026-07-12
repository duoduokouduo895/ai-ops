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
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

/** Only runs fixed, server-owned PromQL and LogQL templates for the controlled Fault Lab. */
@Service
public class RuleBasedDiagnosisService {

    private static final int MAX_RESPONSE_BYTES = 128 * 1024;

    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final String prometheusBaseUrl;
    private final String lokiBaseUrl;
    private final Duration timeout;

    public RuleBasedDiagnosisService(ObjectMapper objectMapper, Environment environment) {
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
        this.prometheusBaseUrl = trimTrailingSlash(environment.getProperty("aidiag.stage3.prometheus-base-url", "http://prometheus:9090"));
        this.lokiBaseUrl = trimTrailingSlash(environment.getProperty("aidiag.stage3.loki-base-url", "http://loki:3100"));
        var timeoutSeconds = environment.getProperty("aidiag.stage3.tool-timeout-seconds", Integer.class, 8);
        if (timeoutSeconds <= 0 || timeoutSeconds > 10) {
            throw new IllegalArgumentException("aidiag.stage3.tool-timeout-seconds must be between 1 and 10");
        }
        this.timeout = Duration.ofSeconds(timeoutSeconds);
    }

    public Stage3DiagnosisRepository.DiagnosisResult diagnose(Stage3DiagnosisRepository.IncidentContext incident) {
        var rule = Rule.forIncident(incident);
        if (rule == null) {
            return new Stage3DiagnosisRepository.DiagnosisResult("NO_DETERMINISTIC_RULE",
                    "当前告警不在 Stage 3 Fault Lab 规则白名单中；报告仅记录证据不足，未推断根因。", BigDecimal.ZERO,
                    "INSUFFICIENT_EVIDENCE", "[]", List.of(), List.of("未匹配确定性规则", "未调用 LLM", "未执行自动修复"));
        }

        var window = timeWindow(incident);
        var metric = collectMetric(rule, incident, window);
        var logs = collectLogs(rule, incident, window);
        var score = BigDecimal.ZERO;
        if (metric.matched()) {
            score = score.add(BigDecimal.valueOf(30));
        }
        if (logs.matched()) {
            score = score.add(BigDecimal.valueOf(25));
        }
        var verification = metric.matched() && logs.matched() ? "SUPPORTED"
                : metric.matched() || logs.matched() ? "WEAKLY_SUPPORTED" : "INSUFFICIENT_EVIDENCE";
        var limitations = new ArrayList<String>();
        limitations.add("当前基线仅使用固定 Prometheus 指标和 Loki 事件日志模板");
        limitations.add("未调用 LLM，未执行自动修复");
        if (!metric.collected()) {
            limitations.add("Prometheus 指标采集失败或超出响应上限");
        }
        if (!logs.collected()) {
            limitations.add("Loki 日志采集失败或超出响应上限");
        }
        if (!"SUPPORTED".equals(verification)) {
            limitations.add("独立证据不足，不能给出确定根因");
        }
        return new Stage3DiagnosisRepository.DiagnosisResult(rule.candidateName, rule.description, score, verification,
                rule.remediationJson, List.of(metric.observation(), logs.observation()), limitations);
    }

    private CollectedObservation collectMetric(Rule rule, Stage3DiagnosisRepository.IncidentContext incident, TimeWindow window) {
        var query = rule.metricQuery;
        var request = json(Map.of("query", query, "start", window.start().toString(), "end", window.end().toString(), "step", "30s"));
        var started = System.nanoTime();
        try {
            var response = get(prometheusBaseUrl + "/api/v1/query_range", Map.of("query", query, "start",
                    Long.toString(window.start().getEpochSecond()), "end", Long.toString(window.end().getEpochSecond()), "step", "30"));
            var maximum = maximumSample(response.path("data").path("result"));
            var matched = maximum.sampleCount() > 0 && maximum.value() >= rule.metricThreshold;
            var rawData = json(Map.of("sampleCount", maximum.sampleCount(), "maximum", maximum.value(), "threshold", rule.metricThreshold));
            var evidence = new Stage3DiagnosisRepository.EvidenceDraft("METRIC", "prometheus", window.start(), window.end(), request,
                    "固定 PromQL 在窗口内观察到最大值 " + maximum.value() + "（阈值 " + rule.metricThreshold + "）", rawData,
                    BigDecimal.valueOf(30), matched);
            return new CollectedObservation(matched, true, new Stage3DiagnosisRepository.ToolObservation("queryServiceMetrics", request,
                    rawData, "SUCCEEDED", elapsedMillis(started), null, evidence));
        } catch (Exception exception) {
            return failed("queryServiceMetrics", request, started);
        }
    }

    private CollectedObservation collectLogs(Rule rule, Stage3DiagnosisRepository.IncidentContext incident, TimeWindow window) {
        var query = "{service_name=\"payment-service\"} |= \"" + rule.logEvent + "\"";
        var request = json(Map.of("query", query, "start", window.start().toString(), "end", window.end().toString(), "limit", 200));
        var started = System.nanoTime();
        try {
            var response = get(lokiBaseUrl + "/loki/api/v1/query_range", Map.of("query", query, "start",
                    Long.toString(window.start().toEpochMilli() * 1_000_000), "end",
                    Long.toString(window.end().toEpochMilli() * 1_000_000), "limit", "200", "direction", "forward"));
            var matchedLines = logLineCount(response.path("data").path("result"));
            var matched = matchedLines > 0;
            var rawData = json(Map.of("matchedLines", matchedLines, "marker", rule.logEvent));
            var evidence = new Stage3DiagnosisRepository.EvidenceDraft("LOG", "loki", window.start(), window.end(), request,
                    "固定 LogQL 匹配到 " + matchedLines + " 条 " + rule.logEvent + " 事件", rawData, BigDecimal.valueOf(25), matched);
            return new CollectedObservation(matched, true, new Stage3DiagnosisRepository.ToolObservation("queryServiceLogs", request,
                    rawData, "SUCCEEDED", elapsedMillis(started), null, evidence));
        } catch (Exception exception) {
            return failed("queryServiceLogs", request, started);
        }
    }

    private CollectedObservation failed(String toolName, String request, long started) {
        return new CollectedObservation(false, false, new Stage3DiagnosisRepository.ToolObservation(toolName, request,
                "{\"outcome\":\"collection_failed\"}", "FAILED", elapsedMillis(started), "collection_failed", null));
    }

    private JsonNode get(String endpoint, Map<String, String> parameters) throws Exception {
        var query = parameters.entrySet().stream().map(entry -> URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8) + "="
                + URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8)).reduce((left, right) -> left + "&" + right).orElse("");
        var request = HttpRequest.newBuilder(URI.create(endpoint + "?" + query)).timeout(timeout).GET().build();
        var response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
        try (InputStream body = response.body()) {
            var bytes = body.readNBytes(MAX_RESPONSE_BYTES + 1);
            if (response.statusCode() != 200 || bytes.length > MAX_RESPONSE_BYTES) {
                throw new IllegalStateException("telemetry query failed");
            }
            var parsed = objectMapper.readTree(bytes);
            if (!"success".equals(parsed.path("status").asText())) {
                throw new IllegalStateException("telemetry query returned an error");
            }
            return parsed;
        }
    }

    private static Maximum maximumSample(JsonNode results) {
        var maximum = 0D;
        var count = 0;
        for (JsonNode series : results) {
            for (JsonNode sample : series.path("values")) {
                try {
                    maximum = Math.max(maximum, Double.parseDouble(sample.path(1).asText()));
                    count++;
                } catch (NumberFormatException ignored) {
                    // An invalid sample is not evidence.
                }
            }
        }
        return new Maximum(maximum, count);
    }

    private static int logLineCount(JsonNode results) {
        var count = 0;
        for (JsonNode stream : results) {
            count += stream.path("values").size();
        }
        return count;
    }

    private static int elapsedMillis(long started) {
        return (int) Math.min(10_000, Duration.ofNanos(System.nanoTime() - started).toMillis());
    }

    private TimeWindow timeWindow(Stage3DiagnosisRepository.IncidentContext incident) {
        var start = incident.startedAt().minus(Duration.ofMinutes(10));
        var eventEnd = incident.lastAlertAt().plus(Duration.ofMinutes(5));
        var end = eventEnd.isBefore(Instant.now()) ? eventEnd : Instant.now();
        return end.isAfter(start) ? new TimeWindow(start, end) : new TimeWindow(Instant.now().minus(Duration.ofMinutes(10)), Instant.now());
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Cannot serialize controlled query", exception);
        }
    }

    private static String trimTrailingSlash(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    private record Rule(String alertName, String metricQuery, double metricThreshold, String logEvent, String candidateName,
            String description, String remediationJson) {
        private static Rule forIncident(Stage3DiagnosisRepository.IncidentContext incident) {
            if (!"payment-service".equals(incident.primaryService())) {
                return null;
            }
            return switch (incident.alertName()) {
                case "FaultLabHikariPoolExhausted" -> new Rule("FaultLabHikariPoolExhausted",
                        "faultlab_hikari_holders_active{service_name=\"payment-service\"}", 4,
                        "faultlab_hikari_connection_timeout", "HIKARI_POOL_EXHAUSTION",
                        "受控 Hikari 持有连接达到池上限，并出现连接获取超时事件。", "[{\"action\":\"Disable the controlled Hikari fault and verify pool recovery\"}]");
                case "FaultLabThreadPoolRejected" -> new Rule("FaultLabThreadPoolRejected",
                        "faultlab_thread_pool_saturated{service_name=\"payment-service\"}", 1,
                        "faultlab_thread_pool_rejected", "THREAD_POOL_EXHAUSTION",
                        "受控 Payment 工作线程池发生任务拒绝。", "[{\"action\":\"Disable the controlled thread-pool fault and verify request recovery\"}]");
                case "FaultLabDownstreamTimeout" -> new Rule("FaultLabDownstreamTimeout",
                        "faultlab_fault_active{service_name=\"payment-service\",fault=\"downstream-timeout\"}", 1,
                        "faultlab_downstream_delay_started", "DOWNSTREAM_TIMEOUT",
                        "受控 Payment 下游延迟处于开启状态，并出现下游延迟事件。", "[{\"action\":\"Disable the controlled downstream-timeout fault and verify Order recovery\"}]");
                case "FaultLabDatabaseSlowQuery" -> new Rule("FaultLabDatabaseSlowQuery",
                        "faultlab_fault_active{service_name=\"payment-service\",fault=\"database-slow-query\"}", 1,
                        "faultlab_database_slow_query_started", "DATABASE_SLOW_QUERY",
                        "受控 Payment 数据库慢查询处于开启状态，并出现慢查询事件。", "[{\"action\":\"Disable the controlled database-slow-query fault and verify Order recovery\"}]");
                default -> null;
            };
        }
    }

    private record TimeWindow(Instant start, Instant end) {
    }

    private record Maximum(double value, int sampleCount) {
    }

    private record CollectedObservation(boolean matched, boolean collected, Stage3DiagnosisRepository.ToolObservation observation) {
    }
}
