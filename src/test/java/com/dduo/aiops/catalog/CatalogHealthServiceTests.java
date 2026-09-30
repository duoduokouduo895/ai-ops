package com.dduo.aiops.catalog;

import static com.dduo.aiops.catalog.CatalogHealthService.HealthState.DOWN;
import static com.dduo.aiops.catalog.CatalogHealthService.HealthState.UNKNOWN;
import static com.dduo.aiops.catalog.CatalogHealthService.HealthState.UP;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class CatalogHealthServiceTests {

    private static final Instant QUERIED_AT = Instant.parse("2026-09-10T06:00:00Z");
    private static final Clock CLOCK = Clock.fixed(QUERIED_AT, ZoneOffset.UTC);
    private static final Duration FRESHNESS = Duration.ofSeconds(60);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final CatalogHealthService healthService = healthService();

    @Test
    void mapsBlackboxLabelsAndConfiguredSignalComponent() {
        var service = aiChatService();
        var telemetry = CatalogHealthService.TelemetrySnapshot.freshStates(Map.of(
                new CatalogHealthService.SignalKey("probe_success", "blackbox-ai-chat", "ai-chat-production",
                        "application"), UP,
                new CatalogHealthService.SignalKey("probe_success", "blackbox-ai-chat", "ai-chat-production",
                        "mysql"), UP,
                new CatalogHealthService.SignalKey("probe_success", "blackbox-ai-chat", "ai-chat-production",
                        "public_ingress"), UP));

        var view = healthService.health(service, telemetry, Instant.EPOCH);

        assertThat(view.status()).isEqualTo(UP);
        assertThat(view.applicationStatus()).isEqualTo(UP);
        assertThat(view.dependencies()).extracting(CatalogHealthService.ProbeView::status).containsOnly(UP);
        assertThat(view.dependencies()).extracting(CatalogHealthService.ProbeView::observedAt).containsOnly(Instant.EPOCH);
        assertThat(view.queriedAt()).isEqualTo(Instant.EPOCH);
    }

    @Test
    void mapsAidiagSelfMonitoringLabels() {
        var service = aidiagService();
        var telemetry = CatalogHealthService.TelemetrySnapshot.freshStates(Map.of(
                new CatalogHealthService.SignalKey("up", "aidiag", "aidiag", ""), UP,
                new CatalogHealthService.SignalKey("aidiag_database_reachable", "aidiag", "aidiag", ""), UP,
                new CatalogHealthService.SignalKey("up", "prometheus", "", ""), UP));

        assertThat(healthService.health(service, telemetry, Instant.EPOCH).status()).isEqualTo(UP);
    }

    @Test
    void rejectsFreshnessOutsideReviewedBounds() {
        assertThatThrownBy(() -> healthServiceWithFreshness(14))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("freshness-seconds");
        assertThatThrownBy(() -> healthServiceWithFreshness(901))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("freshness-seconds");
    }

    @Test
    void staleSampleIsUnknownAndNotDown() {
        var sampledAt = QUERIED_AT.minusSeconds(61);
        var observation = CatalogHealthService.classify(1.0d, sampledAt, QUERIED_AT, FRESHNESS);
        assertThat(observation.state()).isEqualTo(UNKNOWN);
        assertThat(observation.freshness()).isEqualTo("STALE");
        assertThat(observation.reason()).isEqualTo("STALE");
        assertThat(observation.sampledAt()).isEqualTo(sampledAt);
    }

    @Test
    void missingSampleIsUnknown() {
        var telemetry = CatalogHealthService.TelemetrySnapshot.freshStates(Map.of());
        assertThat(telemetry.state("probe_success", "blackbox-ai-chat", "ai-chat-production", "application"))
                .isEqualTo(UNKNOWN);
        assertThat(telemetry.observation("probe_success", "blackbox-ai-chat", "ai-chat-production", "application")
                .reason()).isEqualTo("MISSING_SAMPLE");
    }

    @Test
    void nonFiniteAndFutureSamplesAreUnknown() {
        assertThat(CatalogHealthService.classify(Double.NaN, QUERIED_AT, QUERIED_AT, FRESHNESS).reason())
                .isEqualTo("NON_FINITE_VALUE");
        assertThat(CatalogHealthService.classify(Double.POSITIVE_INFINITY, QUERIED_AT, QUERIED_AT, FRESHNESS).reason())
                .isEqualTo("NON_FINITE_VALUE");
        assertThat(CatalogHealthService.classify(1.0d, QUERIED_AT.plusSeconds(6), QUERIED_AT, FRESHNESS).reason())
                .isEqualTo("FUTURE_SAMPLE");
        assertThat(CatalogHealthService.classify(1.0d, QUERIED_AT.plusSeconds(5), QUERIED_AT, FRESHNESS).state())
                .isEqualTo(UP);
    }

    @Test
    void sameGroupCannotMaskDownOrStaleWithAnotherUp() {
        var down = CatalogHealthService.classify(0.0d, QUERIED_AT.minusSeconds(5), QUERIED_AT, FRESHNESS);
        var up = CatalogHealthService.classify(1.0d, QUERIED_AT.minusSeconds(5), QUERIED_AT, FRESHNESS);
        var stale = CatalogHealthService.classify(1.0d, QUERIED_AT.minusSeconds(120), QUERIED_AT, FRESHNESS);
        assertThat(CatalogHealthService.SignalObservation.aggregate(List.of(up, down)).state()).isEqualTo(DOWN);
        assertThat(CatalogHealthService.SignalObservation.aggregate(List.of(up, stale)).state()).isEqualTo(UNKNOWN);
        assertThat(CatalogHealthService.SignalObservation.aggregate(List.of(up, stale)).reason()).isEqualTo("STALE");
    }

    @Test
    void queryUsesTimestampFunctionAndMapsSampleTimeNotEvaluationTime() throws Exception {
        var sampledAt = QUERIED_AT.minusSeconds(12);
        var captured = new ArrayList<String>();
        var server = prometheusServer((query, evaluationTime) -> {
            captured.add(query + " @ " + evaluationTime);
            if (query.contains("timestamp(")) {
                return vector(timestampMetric("probe_success", "blackbox-ai-chat", "ai-chat-production", "application",
                        "instance-a"), 1_999_999_999L, sampledAt.getEpochSecond() + 0.25d);
            }
            return vector(valueMetric("probe_success", "blackbox-ai-chat", "ai-chat-production", "application",
                    "instance-a"), 1_999_999_999L, "1");
        });
        try {
            var service = serviceAgainst(server, aiChatService());
            var view = service.current().services().getFirst();
            assertThat(captured).anyMatch(item -> item.startsWith("{__name__=~") && item.contains(Long.toString(QUERIED_AT.getEpochSecond())));
            assertThat(captured).anyMatch(item -> item.contains("timestamp(up)") && item.contains("timestamp(probe_success)")
                    && item.contains("timestamp(aidiag_database_reachable)"));
            assertThat(captured).noneMatch(item -> item.contains("timestamp(label_replace"));
            assertThat(captured).noneMatch(item -> item.contains("max by"));
            assertThat(CatalogHealthService.TIMESTAMP_QUERY).doesNotContain("timestamp(label_replace");
            assertThat(view.applicationStatus()).isEqualTo(UP);
            assertThat(view.queriedAt()).isEqualTo(QUERIED_AT);
            assertThat(view.sampledAt()).isEqualTo(Instant.ofEpochMilli(sampledAt.getEpochSecond() * 1000 + 250));
            assertThat(view.freshness()).isEqualTo("FRESH");
            assertThat(view.reason()).isEqualTo("FRESH");
            assertThat(view.dependencies().getFirst().observedAt()).isEqualTo(QUERIED_AT);
            assertThat(view.dependencies().getFirst().status()).isEqualTo(UNKNOWN);
            assertThat(view.dependencies().getFirst().reason()).isEqualTo("MISSING_SAMPLE");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void upstreamFailureAndTimeoutStayUnknown() throws Exception {
        var unreachable = healthService("http://127.0.0.1:1", mockRegistry(aiChatService()),
                HttpClient.newBuilder().connectTimeout(Duration.ofMillis(200)).build());
        var failed = unreachable.current().services().getFirst();
        assertThat(failed.applicationStatus()).isEqualTo(UNKNOWN);
        assertThat(failed.reason()).isEqualTo("QUERY_FAILED");
        assertThat(failed.sampledAt()).isNull();

        var client = mock(HttpClient.class);
        when(client.send(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenThrow(new HttpTimeoutException("timeout"));
        var timedOut = healthService("http://prometheus:9090", mockRegistry(aiChatService()), client);
        var view = timedOut.current().services().getFirst();
        assertThat(view.applicationStatus()).isEqualTo(UNKNOWN);
        assertThat(view.reason()).isEqualTo("QUERY_TIMEOUT");
        assertThat(view.status()).isEqualTo(UNKNOWN);
    }

    @Test
    void extraIdentityLabelsAreNotCollapsedWhenJoiningTimestamps() throws Exception {
        var queriedAt = QUERIED_AT;
        var sampled = Long.toString(queriedAt.minusSeconds(5).getEpochSecond());
        var values = objectMapper.readTree("""
                [
                  {"metric":{"__name__":"up","job":"aidiag","service_name":"aidiag","instance":"a","environment":"prod"},
                   "value":[1999999999,"1"]},
                  {"metric":{"__name__":"up","job":"aidiag","service_name":"aidiag","instance":"a","environment":"lab"},
                   "value":[1999999999,"0"]}
                ]
                """.replace("1999999999", Long.toString(queriedAt.getEpochSecond())));
        var timestamps = objectMapper.readTree("""
                [
                  {"metric":{"metric_name":"up","job":"aidiag","service_name":"aidiag","instance":"a","environment":"prod"},
                   "value":[1999999999,"SAMPLED"]},
                  {"metric":{"metric_name":"up","job":"aidiag","service_name":"aidiag","instance":"a","environment":"lab"},
                   "value":[1999999999,"SAMPLED"]}
                ]
                """.replace("1999999999", Long.toString(queriedAt.getEpochSecond())).replace("SAMPLED", sampled));
        var snapshot = CatalogHealthService.TelemetrySnapshot.fromSamples(queriedAt, values, timestamps, FRESHNESS);
        assertThat(snapshot.state("up", "aidiag", "aidiag", "")).isEqualTo(DOWN);
        assertThat(snapshot.observation("up", "aidiag", "aidiag", "").reason()).isEqualTo("FRESH");
    }

    @Test
    void interruptFlagIsRestored() throws Exception {
        var client = mock(HttpClient.class);
        when(client.send(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenThrow(new InterruptedException("stopped"));
        var service = healthService("http://prometheus:9090", mockRegistry(aiChatService()), client);
        try {
            var view = service.current().services().getFirst();
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
            assertThat(view.reason()).isEqualTo("INTERRUPTED");
            assertThat(view.applicationStatus()).isEqualTo(UNKNOWN);
        } finally {
            Thread.interrupted();
        }
    }

    private CatalogHealthService serviceAgainst(HttpServer server, ServiceCatalog.MonitoredService service) {
        var registry = mockRegistry(service);
        return healthService("http://127.0.0.1:" + server.getAddress().getPort(), registry,
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build());
    }

    private ServiceCatalogRegistry mockRegistry(ServiceCatalog.MonitoredService service) {
        var registry = mock(ServiceCatalogRegistry.class);
        when(registry.snapshot()).thenReturn(new ServiceCatalog.Snapshot(1, Instant.EPOCH, "digest", List.of(service)));
        return registry;
    }

    private CatalogHealthService healthService() {
        return healthService("http://prometheus:9090", mock(ServiceCatalogRegistry.class),
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build());
    }

    private CatalogHealthService healthServiceWithFreshness(int seconds) {
        var environment = new MockEnvironment()
                .withProperty("aidiag.stage3.prometheus-base-url", "http://prometheus:9090")
                .withProperty("aidiag.catalog.freshness-seconds", Integer.toString(seconds));
        return new CatalogHealthService(mock(ServiceCatalogRegistry.class), objectMapper, environment, CLOCK);
    }

    private CatalogHealthService healthService(String baseUrl, ServiceCatalogRegistry registry, HttpClient client) {
        var environment = new MockEnvironment()
                .withProperty("aidiag.stage3.prometheus-base-url", baseUrl)
                .withProperty("aidiag.catalog.freshness-seconds", "60");
        return new CatalogHealthService(registry, objectMapper, environment, CLOCK, client);
    }

    private HttpServer prometheusServer(QueryHandler handler) throws IOException {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/query", exchange -> {
            try {
                var query = queryParam(exchange.getRequestURI().getRawQuery(), "query");
                var time = queryParam(exchange.getRequestURI().getRawQuery(), "time");
                var body = handler.body(query, time).getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, body.length);
                try (OutputStream output = exchange.getResponseBody()) {
                    output.write(body);
                }
            } catch (Exception exception) {
                exchange.sendResponseHeaders(500, -1);
            }
        });
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();
        return server;
    }

    private String vector(ObjectNode metric, long evaluationSeconds, Object sample) throws Exception {
        var root = objectMapper.createObjectNode();
        root.put("status", "success");
        var result = objectMapper.createObjectNode();
        result.set("metric", metric);
        var value = objectMapper.createArrayNode();
        value.add(evaluationSeconds);
        if (sample instanceof Number number) {
            value.add(Double.toString(number.doubleValue()));
        } else {
            value.add(String.valueOf(sample));
        }
        result.set("value", value);
        var results = objectMapper.createArrayNode();
        results.add(result);
        var data = objectMapper.createObjectNode();
        data.put("resultType", "vector");
        data.set("result", results);
        root.set("data", data);
        return objectMapper.writeValueAsString(root);
    }

    private ObjectNode valueMetric(String name, String job, String service, String component, String instance) {
        var metric = objectMapper.createObjectNode();
        metric.put("__name__", name);
        metric.put("job", job);
        metric.put("service_name", service);
        metric.put("component", component);
        metric.put("instance", instance);
        return metric;
    }

    private ObjectNode timestampMetric(String name, String job, String service, String component, String instance) {
        var metric = valueMetric(name, job, service, component, instance);
        metric.remove("__name__");
        metric.put("metric_name", name);
        return metric;
    }

    private static String queryParam(String rawQuery, String name) {
        if (rawQuery == null) {
            return "";
        }
        for (var part : rawQuery.split("&")) {
            var pieces = part.split("=", 2);
            if (pieces.length == 2 && name.equals(pieces[0])) {
                return URLDecoder.decode(pieces[1], StandardCharsets.UTF_8);
            }
        }
        return "";
    }

    private static ServiceCatalog.MonitoredService aiChatService() {
        return new ServiceCatalog.MonitoredService("ai-chat", "AI-chat", "production", "http://unused",
                "http://unused", "ai-chat-production", "ai-chat-v1", null, List.of(
                        new ServiceCatalog.DependencyProbe("mysql", "tcp", "mysql:3306", null),
                        new ServiceCatalog.DependencyProbe("public-api", "http", "https://api.dduo.me/actuator/health",
                                "public_ingress")));
    }

    private static ServiceCatalog.MonitoredService aidiagService() {
        return new ServiceCatalog.MonitoredService("aidiag", "AIDiag", "production", "http://unused",
                "http://unused", "aidiag", "aidiag-v1", null, List.of(
                        new ServiceCatalog.DependencyProbe("postgres", "tcp", "postgres:5432", null),
                        new ServiceCatalog.DependencyProbe("prometheus", "http", "http://prometheus:9090", null)));
    }

    @FunctionalInterface
    private interface QueryHandler {
        String body(String query, String evaluationTime) throws Exception;
    }
}
