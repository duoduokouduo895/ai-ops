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

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.dduo.aiops.diagnosis.Stage3DiagnosisRepository.IncidentContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class RuleBasedDiagnosisServiceTests {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final List<String> metricQueries = new ArrayList<>();
    private HttpServer server;
    private RuleBasedDiagnosisService service;

    @BeforeEach
    void startTelemetryStub() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/query_range", exchange -> {
            var query = queryParameter(exchange.getRequestURI(), "query");
            metricQueries.add(query);
            var value = query.startsWith("faultlab_hikari_holders_active") ? "4" : "1";
            respond(exchange, "{\"status\":\"success\",\"data\":{\"resultType\":\"matrix\",\"result\":[{\"values\":[[0,\""
                    + value + "\"]]}]}}");
        });
        server.createContext("/loki/api/v1/query_range", exchange -> respond(exchange, """
                {"status":"success","data":{"resultType":"streams","result":[{"values":[["0","fixed event"]]}]}}
                """));
        server.start();
        var baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        service = new RuleBasedDiagnosisService(objectMapper, new MockEnvironment()
                .withProperty("aidiag.stage3.prometheus-base-url", baseUrl)
                .withProperty("aidiag.stage3.loki-base-url", baseUrl));
    }

    @AfterEach
    void stopTelemetryStub() {
        server.stop(0);
    }

    @Test
    void supportsEveryWhitelistedFaultWithMetricAndLogEvidence() {
        var expectedQueries = List.of(
                "faultlab_hikari_holders_active{service_name=\"payment-service\"}",
                "faultlab_thread_pool_saturated{service_name=\"payment-service\"}",
                "faultlab_fault_active{service_name=\"payment-service\",fault=\"downstream-timeout\"}",
                "faultlab_fault_active{service_name=\"payment-service\",fault=\"database-slow-query\"}");

        for (var alertName : List.of("FaultLabHikariPoolExhausted", "FaultLabThreadPoolRejected", "FaultLabDownstreamTimeout",
                "FaultLabDatabaseSlowQuery")) {
            var result = service.diagnose(incident(alertName));

            assertThat(result.verificationStatus()).isEqualTo("SUPPORTED");
            assertThat(result.supportScore()).isEqualByComparingTo("55");
            assertThat(result.observations()).hasSize(2);
        }
        assertThat(metricQueries).containsExactlyElementsOf(expectedQueries);
    }

    @Test
    void refusesToQueryTelemetryForUnknownAlerts() {
        var result = service.diagnose(incident("UnexpectedProductionAlert"));

        assertThat(result.verificationStatus()).isEqualTo("INSUFFICIENT_EVIDENCE");
        assertThat(result.observations()).isEmpty();
        assertThat(metricQueries).isEmpty();
    }

    private static IncidentContext incident(String alertName) {
        var now = Instant.now();
        return new IncidentContext(UUID.randomUUID(), "test", "payment-service", alertName, "WARNING", now.minusSeconds(60), now);
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
