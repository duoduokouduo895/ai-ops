package com.dduo.aiops.monitoring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import com.dduo.aiops.catalog.ServiceCatalog;
import com.dduo.aiops.catalog.ServiceCatalogRegistry;
import com.dduo.aiops.telemetry.PrometheusClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class ServiceSignalsServiceTests {

    private static final Instant NOW = Instant.parse("2026-09-30T12:00:10Z");
    private static final ServiceCatalog.MonitoredService AI_CHAT = new ServiceCatalog.MonitoredService("ai-chat",
            "AI-chat", "production", "http://ai-chat/actuator/health", "http://ai-chat/actuator/prometheus",
            "ai-chat-production", SignalProfiles.AI_CHAT, null, List.of());

    private HttpServer server;

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void mapsThresholdBreachAndFiringAlertOntoTheMatchingSignal() throws IOException {
        var service = serviceAgainst(query -> {
            if (query.startsWith("ALERTS")) {
                return vector("{\"alertname\":\"AiChatModelTimeout\",\"alertstate\":\"firing\"}");
            }
            if (query.contains("outcome=\"timeout\"")) {
                return matrix("{}", "[[1790000000,\"0\"],[1790000030,\"2\"]]");
            }
            if (query.startsWith("sum by (outcome)")) {
                return matrixOf(series("{\"outcome\":\"timeout\"}", "[[1790000030,\"0.1\"]]"),
                        series("{\"outcome\":\"success\"}", "[[1790000030,\"1.5\"]]"));
            }
            return matrix("{}", "[]");
        });

        var view = service.signals("ai-chat", SignalRange.ONE_HOUR).orElseThrow();

        assertThat(view.otelServiceName()).isEqualTo("ai-chat-production");
        assertThat(view.stepSeconds()).isEqualTo(30);
        assertThat(Duration.between(view.start(), view.end())).isEqualTo(Duration.ofHours(1));
        var timeouts = signal(view, "model-timeouts");
        assertThat(timeouts.status()).isEqualTo("BREACH");
        assertThat(timeouts.current()).isEqualTo(2.0);
        assertThat(timeouts.alertState()).isEqualTo("FIRING");
        assertThat(signal(view, "r2dbc-pending").alertState()).isEqualTo("INACTIVE");
        assertThat(signal(view, "r2dbc-pending").status()).isEqualTo("NO_DATA");
        assertThat(signal(view, "chat-throughput").series()).extracting(ServiceSignalsService.SeriesView::label)
                .containsExactly("success", "timeout");
        assertThat(signal(view, "ttft-p95").alertState()).isNull();
    }

    @Test
    void reportsQueryFailuresPerSignalAndUnknownAlertStateWithoutFailingTheView() throws IOException {
        var service = serviceAgainst(query -> query.startsWith("ALERTS") || query.contains("ttft") ? null : matrix("{}",
                "[[1790000030,\"1\"]]"));

        var view = service.signals("ai-chat", SignalRange.SIX_HOURS).orElseThrow();

        assertThat(signal(view, "ttft-p95").status()).isEqualTo("QUERY_FAILED");
        assertThat(signal(view, "ttft-p95").reason()).isEqualTo("QUERY_FAILED");
        assertThat(signal(view, "availability").status()).isEqualTo("OK");
        assertThat(signal(view, "availability").alertState()).isEqualTo("UNKNOWN");
    }

    @Test
    void cachesResultsBrieflyAndRejectsServicesWithoutASignalProfile() throws IOException {
        var calls = new AtomicInteger();
        var service = serviceAgainst(query -> {
            calls.incrementAndGet();
            return query.startsWith("ALERTS") ? vector("") : matrix("{}", "[]");
        });

        service.signals("ai-chat", SignalRange.ONE_DAY);
        var afterFirst = calls.get();
        service.signals("ai-chat", SignalRange.ONE_DAY);

        assertThat(afterFirst).isEqualTo(SignalProfiles.forProfile(SignalProfiles.AI_CHAT).size() + 1);
        assertThat(calls.get()).isEqualTo(afterFirst);
        assertThat(service.signals("aidiag", SignalRange.ONE_HOUR)).isEmpty();
        assertThat(service.signals("missing", SignalRange.ONE_HOUR)).isEmpty();
    }

    private static ServiceSignalsService.SignalView signal(ServiceSignalsService.SignalsView view, String id) {
        return view.signals().stream().filter(item -> item.id().equals(id)).findFirst().orElseThrow();
    }

    private ServiceSignalsService serviceAgainst(Function<String, String> handler) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/", exchange -> {
            var rawQuery = exchange.getRequestURI().getRawQuery();
            var query = "";
            for (var part : rawQuery.split("&")) {
                if (part.startsWith("query=")) {
                    query = URLDecoder.decode(part.substring(6), StandardCharsets.UTF_8);
                }
            }
            var body = handler.apply(query);
            var bytes = (body == null ? "{\"status\":\"error\"}" : body).getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(body == null ? 400 : 200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        var prometheus = new PrometheusClient(new ObjectMapper(), "http://127.0.0.1:" + server.getAddress().getPort(),
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build());
        var registry = mock(ServiceCatalogRegistry.class);
        var aidiag = new ServiceCatalog.MonitoredService("aidiag", "AIDiag", "production", "h", "m", "aidiag",
                "aidiag-v1", null, List.of());
        when(registry.snapshot()).thenReturn(new ServiceCatalog.Snapshot(1, NOW, "digest", List.of(AI_CHAT, aidiag)));
        return new ServiceSignalsService(registry, prometheus, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static String matrix(String labels, String values) {
        return matrixOf(series(labels, values));
    }

    private static String matrixOf(String... series) {
        return "{\"status\":\"success\",\"data\":{\"resultType\":\"matrix\",\"result\":["
                + String.join(",", series) + "]}}";
    }

    private static String series(String labels, String values) {
        return "{\"metric\":" + labels + ",\"values\":" + values + "}";
    }

    private static String vector(String labels) {
        var result = labels.isEmpty() ? "" : "{\"metric\":" + labels + ",\"value\":[1790000030,\"1\"]}";
        return "{\"status\":\"success\",\"data\":{\"resultType\":\"vector\",\"result\":[" + result + "]}}";
    }
}
