package com.dduo.aiops.diagnosis;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import com.dduo.aiops.diagnosis.Stage3DiagnosisRepository.IncidentContext;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class RuleBasedDiagnosisServiceTests {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final List<String> metricQueries = new ArrayList<>();
    private final List<String> logQueries = new ArrayList<>();
    private final List<String> traceQueries = new ArrayList<>();
    private final AtomicReference<String> metricResponse = new AtomicReference<>(metric("20"));
    private final AtomicReference<String> logResponse = new AtomicReference<>(logs(1));
    private final AtomicReference<String> traceResponse = new AtomicReference<>(traces(1));
    private HttpServer server;
    private RuleBasedDiagnosisService service;

    @BeforeEach
    void startTelemetryStub() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/query_range", exchange -> {
            metricQueries.add(queryParameter(exchange.getRequestURI(), "query"));
            respond(exchange, metricResponse.get());
        });
        server.createContext("/loki/api/v1/query_range", exchange -> {
            logQueries.add(queryParameter(exchange.getRequestURI(), "query"));
            respond(exchange, logResponse.get());
        });
        server.createContext("/api/search", exchange -> {
            traceQueries.add(queryParameter(exchange.getRequestURI(), "q"));
            respond(exchange, traceResponse.get());
        });
        server.start();
        var baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        var environment = new MockEnvironment().withProperty("aidiag.stage3.prometheus-base-url", baseUrl)
                .withProperty("aidiag.stage3.loki-base-url", baseUrl).withProperty("aidiag.stage3.tempo-base-url", baseUrl);
        service = new RuleBasedDiagnosisService(
                new DiagnosisRuleRegistry(List.of(new FaultLabDiagnosisRuleProvider(), new AiChatDiagnosisRuleProvider())),
                new TelemetryEvidenceCollector(objectMapper, environment));
    }

    @AfterEach
    void stopTelemetryStub() {
        server.stop(0);
    }

    @Test
    void preservesEveryFaultLabRuleAndAddsTraceEvidence() {
        var expectedQueries = List.of(
                "faultlab_hikari_holders_active{service_name=\"payment-service\"}",
                "faultlab_thread_pool_saturated{service_name=\"payment-service\"}",
                "faultlab_fault_active{service_name=\"payment-service\",fault=\"downstream-timeout\"}",
                "faultlab_fault_active{service_name=\"payment-service\",fault=\"database-slow-query\"}");

        for (var alertName : List.of("FaultLabHikariPoolExhausted", "FaultLabThreadPoolRejected", "FaultLabDownstreamTimeout",
                "FaultLabDatabaseSlowQuery")) {
            var result = service.diagnose(incident("payment-service", alertName));
            assertThat(result.verificationStatus()).isEqualTo("SUPPORTED");
            assertThat(result.supportScore()).isEqualByComparingTo("75");
            assertThat(result.observations()).extracting(Stage3DiagnosisRepository.ToolObservation::toolName)
                    .containsExactly("queryServiceMetrics", "queryServiceLogs", "queryServiceTraces");
        }
        assertThat(metricQueries).containsExactlyElementsOf(expectedQueries);
    }

    @Test
    void registersExactlyTheFourAiChatRuleContracts() {
        for (var alertName : List.of("AiChatModelTimeout", "AiChatR2dbcPoolExhaustion", "AiChatDocumentBacklog",
                "AiChatSearchUnavailable")) {
            var result = service.diagnose(incident("ai-chat-candidate", alertName));
            assertThat(result.verificationStatus()).isEqualTo("SUPPORTED");
            assertThat(result.groundTruth()).isEqualTo("UNSPECIFIED");
        }
        assertThat(metricQueries).hasSize(4).allMatch(query -> query.contains("ai-chat-candidate"));
        assertThat(logQueries).hasSize(4).allMatch(query -> query.contains("service_name=\"ai-chat-candidate\""));
        assertThat(traceQueries).hasSize(4).allMatch(query -> query.contains("resource.service.name = \"ai-chat-candidate\""));
    }

    @Test
    void mirrorsTheFourContractsForProductionWithoutInterpolatingIncidentInput() {
        for (var alertName : List.of("AiChatModelTimeout", "AiChatR2dbcPoolExhaustion", "AiChatDocumentBacklog",
                "AiChatSearchUnavailable")) {
            var result = service.diagnose(incident("ai-chat-production", alertName));
            assertThat(result.verificationStatus()).isEqualTo("SUPPORTED");
            assertThat(result.groundTruth()).isEqualTo("UNSPECIFIED");
        }
        assertThat(metricQueries).hasSize(4).allMatch(query -> query.contains("ai-chat-production"));
        assertThat(logQueries).hasSize(4).allMatch(query -> query.contains("service_name=\"ai-chat-production\""));
        assertThat(traceQueries).hasSize(4)
                .allMatch(query -> query.contains("resource.service.name = \"ai-chat-production\""));
    }

    @Test
    void mirrorsTheFourContractsForTheIsolatedStageFiveLab() {
        for (var alertName : List.of("AiChatModelTimeout", "AiChatR2dbcPoolExhaustion", "AiChatDocumentBacklog",
                "AiChatSearchUnavailable")) {
            var result = service.diagnose(incident("ai-chat-stage5-lab", alertName));
            assertThat(result.verificationStatus()).isEqualTo("SUPPORTED");
            assertThat(result.groundTruth()).isNotBlank().isNotEqualTo("UNSPECIFIED");
        }
        assertThat(metricQueries).hasSize(4).allMatch(query -> query.contains("ai-chat-stage5-lab"));
        assertThat(logQueries).hasSize(4).allMatch(query -> query.contains("service_name=\"ai-chat-stage5-lab\""));
        assertThat(traceQueries).hasSize(4)
                .allMatch(query -> query.contains("resource.service.name = \"ai-chat-stage5-lab\""));
    }

    @Test
    void unknownOrInjectedServiceAndAlertNeverReachTelemetry() {
        assertThat(service.diagnose(incident("ai-chat-candidate", "UnexpectedProductionAlert")).verificationStatus())
                .isEqualTo("INSUFFICIENT_EVIDENCE");
        assertThat(service.diagnose(incident("ai-chat-candidate\"} |= \"secret", "AiChatModelTimeout")).verificationStatus())
                .isEqualTo("INSUFFICIENT_EVIDENCE");
        assertThat(service.diagnose(incident("ai-chat-candidate", "AiChatModelTimeout|unwrap secret")).verificationStatus())
                .isEqualTo("INSUFFICIENT_EVIDENCE");
        assertThat(metricQueries).isEmpty();
        assertThat(logQueries).isEmpty();
        assertThat(traceQueries).isEmpty();
    }

    @Test
    void missingMetricOrLogCannotCreateASupportedRootCause() {
        metricResponse.set(metricResult("[]"));
        var missingMetric = service.diagnose(incident("ai-chat-candidate", "AiChatModelTimeout"));
        assertThat(missingMetric.verificationStatus()).isEqualTo("WEAKLY_SUPPORTED");
        assertThat(missingMetric.isSupported()).isFalse();

        metricResponse.set(metric("20"));
        logResponse.set(logs(0));
        var missingLog = service.diagnose(incident("ai-chat-candidate", "AiChatModelTimeout"));
        assertThat(missingLog.verificationStatus()).isEqualTo("WEAKLY_SUPPORTED");
        assertThat(missingLog.isSupported()).isFalse();
    }

    @Test
    void oversizedTelemetryResponseIsAuditedAsCollectionFailure() {
        metricResponse.set("x".repeat(TelemetryEvidenceCollector.MAX_RESPONSE_BYTES + 1));
        var result = service.diagnose(incident("ai-chat-candidate", "AiChatModelTimeout"));

        assertThat(result.verificationStatus()).isEqualTo("WEAKLY_SUPPORTED");
        assertThat(result.observations().getFirst().status()).isEqualTo("FAILED");
        assertThat(result.observations().getFirst().evidence()).isNull();
        assertThat(result.limitations()).anyMatch(item -> item.contains("Prometheus"));
    }

    @Test
    void tempoFailureDoesNotBlockMetricAndLogP0Report() {
        traceResponse.set("x".repeat(TelemetryEvidenceCollector.MAX_RESPONSE_BYTES + 1));
        var result = service.diagnose(incident("ai-chat-candidate", "AiChatModelTimeout"));

        assertThat(result.verificationStatus()).isEqualTo("SUPPORTED");
        assertThat(result.supportScore()).isEqualByComparingTo("55");
        assertThat(result.observations().get(2).status()).isEqualTo("FAILED");
        assertThat(result.limitations()).anyMatch(item -> item.contains("已降级"));
    }

    @Test
    void tempoEvidenceKeepsAtMostFiftyValidCompactTraceSummaries() throws Exception {
        traceResponse.set(traces(65));
        var result = service.diagnose(incident("ai-chat-candidate", "AiChatModelTimeout"));
        var raw = objectMapper.readTree(result.observations().get(2).evidence().rawDataJson());

        assertThat(raw.path("traceCount").asInt()).isEqualTo(50);
        assertThat(raw.path("traces")).hasSize(50);
        assertThat(result.observations().get(2).evidence().rawDataJson()).doesNotContain("prompt", "authorization", "cookie");
    }

    private static IncidentContext incident(String serviceName, String alertName) {
        var now = Instant.now();
        return new IncidentContext(UUID.randomUUID(), "test", serviceName, alertName, "WARNING", now.minusSeconds(60), now);
    }

    private static String metric(String value) {
        return metricResult("[{\"values\":[[0,\"" + value + "\"]]}]");
    }

    private static String metricResult(String result) {
        return "{\"status\":\"success\",\"data\":{\"resultType\":\"matrix\",\"result\":" + result + "}}";
    }

    private static String logs(int count) {
        var values = new ArrayList<String>();
        for (var index = 0; index < count; index++) {
            values.add("[\"" + index + "\",\"fixed event\"]");
        }
        return "{\"status\":\"success\",\"data\":{\"resultType\":\"streams\",\"result\":[{\"values\":["
                + String.join(",", values) + "]}]}}";
    }

    private static String traces(int count) {
        var values = new ArrayList<String>();
        for (var index = 0; index < count; index++) {
            values.add("{\"traceID\":\"" + String.format("%032x", index + 1)
                    + "\",\"rootServiceName\":\"ai-chat-candidate\",\"rootTraceName\":\"POST /chat\",\"durationMs\":12000}");
        }
        return "{\"traces\":[" + String.join(",", values) + "]}";
    }

    private static void respond(HttpExchange exchange, String payload) throws IOException {
        var bytes = payload.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private static String queryParameter(URI uri, String name) {
        for (var parameter : uri.getRawQuery().split("&")) {
            var separator = parameter.indexOf('=');
            if (separator > 0 && name.equals(java.net.URLDecoder.decode(parameter.substring(0, separator), StandardCharsets.UTF_8))) {
                return java.net.URLDecoder.decode(parameter.substring(separator + 1), StandardCharsets.UTF_8);
            }
        }
        throw new IllegalArgumentException("missing query parameter " + name);
    }
}
