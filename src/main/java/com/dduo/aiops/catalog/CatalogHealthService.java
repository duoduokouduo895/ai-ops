package com.dduo.aiops.catalog;

import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

/** Reads one server-owned PromQL snapshot and maps it onto the validated catalog without N+1 network requests. */
@Service
public class CatalogHealthService {

    private static final int MAX_RESPONSE_BYTES = 128 * 1024;
    private static final String SNAPSHOT_QUERY = "max by (__name__, job, service_name, component) "
            + "({__name__=~\"up|probe_success|aidiag_database_reachable\"})";

    private final ServiceCatalogRegistry registry;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final String prometheusBaseUrl;

    public CatalogHealthService(ServiceCatalogRegistry registry, ObjectMapper objectMapper, Environment environment) {
        this.registry = registry;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
        var configured = environment.getProperty("aidiag.stage3.prometheus-base-url", "http://prometheus:9090");
        this.prometheusBaseUrl = configured.endsWith("/") ? configured.substring(0, configured.length() - 1) : configured;
    }

    public CatalogView current() {
        var snapshot = registry.snapshot();
        var observedAt = Instant.now();
        var telemetry = querySnapshot();
        var services = snapshot.services().stream().map(service -> health(service, telemetry, observedAt)).toList();
        return new CatalogView(snapshot.version(), snapshot.compiledAt(), snapshot.sourceDigest(), services);
    }

    ServiceView health(ServiceCatalog.MonitoredService service, TelemetrySnapshot telemetry, Instant observedAt) {
        var probes = new ArrayList<ProbeView>();
        for (var dependency : service.dependencies()) {
            probes.add(new ProbeView(dependency.id(), dependency.kind(), dependencyState(service, dependency, telemetry), observedAt));
        }
        var application = "aidiag".equals(service.id())
                ? telemetry.state("up", "aidiag", service.otelServiceName(), "")
                : telemetry.state("probe_success", "blackbox-ai-chat", service.otelServiceName(), "application");
        var overall = application == HealthState.UP && probes.stream().allMatch(probe -> probe.status() == HealthState.UP)
                ? HealthState.UP
                : application == HealthState.DOWN || probes.stream().anyMatch(probe -> probe.status() == HealthState.DOWN)
                        ? HealthState.DOWN : HealthState.UNKNOWN;
        return new ServiceView(service.id(), service.displayName(), service.environment(), service.otelServiceName(),
                service.diagnosisProfile(), service.grafanaUrl(), overall, application, List.copyOf(probes));
    }

    private static HealthState dependencyState(ServiceCatalog.MonitoredService service,
            ServiceCatalog.DependencyProbe dependency, TelemetrySnapshot telemetry) {
        if (!"aidiag".equals(service.id())) {
            var component = dependency.signalComponent() == null ? dependency.id() : dependency.signalComponent();
            return telemetry.state("probe_success", "blackbox-ai-chat", service.otelServiceName(), component);
        }
        return switch (dependency.id()) {
            case "postgres" -> telemetry.state("aidiag_database_reachable", "aidiag", service.otelServiceName(), "");
            case "prometheus", "loki", "tempo", "alertmanager" -> telemetry.state("up", dependency.id(), "", "");
            default -> HealthState.UNKNOWN;
        };
    }

    private TelemetrySnapshot querySnapshot() {
        try {
            var endpoint = prometheusBaseUrl + "/api/v1/query?query="
                    + URLEncoder.encode(SNAPSHOT_QUERY, StandardCharsets.UTF_8);
            var request = HttpRequest.newBuilder(URI.create(endpoint)).timeout(Duration.ofSeconds(4)).GET().build();
            var response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream body = response.body()) {
                var bytes = body.readNBytes(MAX_RESPONSE_BYTES + 1);
                if (response.statusCode() != 200 || bytes.length > MAX_RESPONSE_BYTES) {
                    return TelemetrySnapshot.unavailable();
                }
                var parsed = objectMapper.readTree(bytes);
                var results = parsed.path("data").path("result");
                if (!"success".equals(parsed.path("status").asText()) || !results.isArray() || results.size() > 5000) {
                    return TelemetrySnapshot.unavailable();
                }
                var values = new HashMap<SignalKey, HealthState>();
                for (var result : results) {
                    var metric = result.path("metric");
                    var key = new SignalKey(metric.path("__name__").asText(""), metric.path("job").asText(""),
                            metric.path("service_name").asText(""), metric.path("component").asText(""));
                    try {
                        var value = Double.parseDouble(result.path("value").path(1).asText("NaN"));
                        values.put(key, value >= 0.5 ? HealthState.UP : HealthState.DOWN);
                    } catch (NumberFormatException ignored) {
                        // Non-numeric samples remain UNKNOWN.
                    }
                }
                return new TelemetrySnapshot(Map.copyOf(values));
            }
        } catch (Exception exception) {
            return TelemetrySnapshot.unavailable();
        }
    }

    public enum HealthState { UP, DOWN, UNKNOWN }
    public record CatalogView(int version, Instant compiledAt, String sourceDigest, List<ServiceView> services) { }
    public record ServiceView(String id, String displayName, String environment, String otelServiceName,
            String diagnosisProfile, String grafanaUrl, HealthState status, HealthState applicationStatus,
            List<ProbeView> dependencies) { }
    public record ProbeView(String id, String kind, HealthState status, Instant observedAt) { }

    record SignalKey(String metric, String job, String serviceName, String component) { }
    record TelemetrySnapshot(Map<SignalKey, HealthState> values) {
        static TelemetrySnapshot unavailable() { return new TelemetrySnapshot(Map.of()); }
        HealthState state(String metric, String job, String serviceName, String component) {
            return values.getOrDefault(new SignalKey(metric, job, serviceName, component), HealthState.UNKNOWN);
        }
    }
}
