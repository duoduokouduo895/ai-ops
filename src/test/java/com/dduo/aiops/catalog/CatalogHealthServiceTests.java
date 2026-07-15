package com.dduo.aiops.catalog;

import static com.dduo.aiops.catalog.CatalogHealthService.HealthState.UP;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.Environment;

class CatalogHealthServiceTests {

    private final CatalogHealthService healthService = healthService();

    @Test
    void mapsBlackboxLabelsAndConfiguredSignalComponent() {
        var service = new ServiceCatalog.MonitoredService("ai-chat", "AI-chat", "production", "http://unused",
                "http://unused", "ai-chat-production", "ai-chat-v1", null, List.of(
                        new ServiceCatalog.DependencyProbe("mysql", "tcp", "mysql:3306", null),
                        new ServiceCatalog.DependencyProbe("public-api", "http", "https://api.dduo.me/actuator/health",
                                "public_ingress")));
        var telemetry = new CatalogHealthService.TelemetrySnapshot(Map.of(
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
    }

    @Test
    void mapsAidiagSelfMonitoringLabels() {
        var service = new ServiceCatalog.MonitoredService("aidiag", "AIDiag", "production", "http://unused",
                "http://unused", "aidiag", "aidiag-v1", null, List.of(
                        new ServiceCatalog.DependencyProbe("postgres", "tcp", "postgres:5432", null),
                        new ServiceCatalog.DependencyProbe("prometheus", "http", "http://prometheus:9090", null)));
        var telemetry = new CatalogHealthService.TelemetrySnapshot(Map.of(
                new CatalogHealthService.SignalKey("up", "aidiag", "aidiag", ""), UP,
                new CatalogHealthService.SignalKey("aidiag_database_reachable", "aidiag", "aidiag", ""), UP,
                new CatalogHealthService.SignalKey("up", "prometheus", "", ""), UP));

        assertThat(healthService.health(service, telemetry, Instant.EPOCH).status()).isEqualTo(UP);
    }

    private static CatalogHealthService healthService() {
        var environment = mock(Environment.class);
        when(environment.getProperty("aidiag.stage3.prometheus-base-url", "http://prometheus:9090"))
                .thenReturn("http://prometheus:9090");
        return new CatalogHealthService(mock(ServiceCatalogRegistry.class), new ObjectMapper(), environment);
    }
}
