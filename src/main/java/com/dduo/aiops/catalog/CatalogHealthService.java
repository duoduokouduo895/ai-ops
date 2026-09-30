package com.dduo.aiops.catalog;

import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

import java.util.TreeMap;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

/**
 * Reads one server-owned PromQL snapshot and maps it onto the validated catalog without N+1 network requests.
 * Sample time comes from timestamp() applied to raw vector selectors. Wrapping label_replace inside timestamp()
 * would align results to the evaluation time instead of the original scrape time.
 */
@Service
public class CatalogHealthService {

    static final int MAX_RESPONSE_BYTES = 128 * 1024;
    static final int MAX_SAMPLES = 5000;
    static final Duration FUTURE_SKEW = Duration.ofSeconds(5);
    static final String VALUE_QUERY = "{__name__=~\"up|probe_success|aidiag_database_reachable\"}";
    static final String TIMESTAMP_QUERY = String.join(" or ",
            "label_replace(timestamp(up),\"metric_name\",\"up\",\"\",\"\")",
            "label_replace(timestamp(probe_success),\"metric_name\",\"probe_success\",\"\",\"\")",
            "label_replace(timestamp(aidiag_database_reachable),\"metric_name\",\"aidiag_database_reachable\",\"\",\"\")");

    private final ServiceCatalogRegistry registry;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final String prometheusBaseUrl;
    private final Duration freshness;
    private final Clock clock;

    @Autowired
    public CatalogHealthService(ServiceCatalogRegistry registry, ObjectMapper objectMapper, Environment environment,
            Clock clock) {
        this(registry, objectMapper, environment, clock,
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build());
    }

    CatalogHealthService(ServiceCatalogRegistry registry, ObjectMapper objectMapper, Environment environment,
            Clock clock, HttpClient httpClient) {
        this.registry = registry;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.httpClient = httpClient;
        var configured = environment.getProperty("aidiag.stage3.prometheus-base-url", "http://prometheus:9090");
        this.prometheusBaseUrl = configured.endsWith("/") ? configured.substring(0, configured.length() - 1) : configured;
        var freshnessSeconds = environment.getProperty("aidiag.catalog.freshness-seconds", Integer.class, 60);
        if (freshnessSeconds == null || freshnessSeconds < 15 || freshnessSeconds > 900) {
            throw new IllegalArgumentException("aidiag.catalog.freshness-seconds must be between 15 and 900");
        }
        this.freshness = Duration.ofSeconds(freshnessSeconds);
    }

    public CatalogView current() {
        var snapshot = registry.snapshot();
        var queriedAt = clock.instant();
        var telemetry = querySnapshot(queriedAt);
        var services = snapshot.services().stream().map(service -> health(service, telemetry, queriedAt)).toList();
        return new CatalogView(snapshot.version(), snapshot.compiledAt(), snapshot.sourceDigest(), services, queriedAt);
    }

    ServiceView health(ServiceCatalog.MonitoredService service, TelemetrySnapshot telemetry, Instant queriedAt) {
        var probes = new ArrayList<ProbeView>();
        for (var dependency : service.dependencies()) {
            probes.add(probeView(dependency, dependencyObservation(service, dependency, telemetry), queriedAt));
        }
        var application = applicationObservation(service, telemetry);
        var overall = combine(application.state(), probes);
        return new ServiceView(service.id(), service.displayName(), service.environment(), service.otelServiceName(),
                service.diagnosisProfile(), service.grafanaUrl(), overall, application.state(), List.copyOf(probes),
                queriedAt, application.sampledAt(), application.freshness(), application.reason());
    }

    private static ProbeView probeView(ServiceCatalog.DependencyProbe dependency, SignalObservation observation,
            Instant queriedAt) {
        return new ProbeView(dependency.id(), dependency.kind(), observation.state(), queriedAt, queriedAt,
                observation.sampledAt(), observation.freshness(), observation.reason());
    }

    private static HealthState combine(HealthState application, List<ProbeView> probes) {
        if (application == HealthState.UP && probes.stream().allMatch(probe -> probe.status() == HealthState.UP)) {
            return HealthState.UP;
        }
        if (application == HealthState.DOWN || probes.stream().anyMatch(probe -> probe.status() == HealthState.DOWN)) {
            return HealthState.DOWN;
        }
        return HealthState.UNKNOWN;
    }

    private static SignalObservation applicationObservation(ServiceCatalog.MonitoredService service,
            TelemetrySnapshot telemetry) {
        return "aidiag".equals(service.id())
                ? telemetry.observation("up", "aidiag", service.otelServiceName(), "")
                : telemetry.observation("probe_success", "blackbox-ai-chat", service.otelServiceName(), "application");
    }

    private static SignalObservation dependencyObservation(ServiceCatalog.MonitoredService service,
            ServiceCatalog.DependencyProbe dependency, TelemetrySnapshot telemetry) {
        if (!"aidiag".equals(service.id())) {
            var component = dependency.signalComponent() == null ? dependency.id() : dependency.signalComponent();
            return telemetry.observation("probe_success", "blackbox-ai-chat", service.otelServiceName(), component);
        }
        return switch (dependency.id()) {
            case "postgres" -> telemetry.observation("aidiag_database_reachable", "aidiag", service.otelServiceName(), "");
            case "prometheus", "loki", "tempo", "alertmanager" -> telemetry.observation("up", dependency.id(), "", "");
            default -> SignalObservation.missing();
        };
    }

    TelemetrySnapshot querySnapshot(Instant queriedAt) {
        try {
            var values = queryPrometheus(VALUE_QUERY, queriedAt);
            var timestamps = queryPrometheus(TIMESTAMP_QUERY, queriedAt);
            return TelemetrySnapshot.fromSamples(queriedAt, values, timestamps, freshness);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return TelemetrySnapshot.unavailable(queriedAt, "INTERRUPTED");
        } catch (HttpTimeoutException exception) {
            return TelemetrySnapshot.unavailable(queriedAt, "QUERY_TIMEOUT");
        } catch (QueryFailedException exception) {
            return TelemetrySnapshot.unavailable(queriedAt, exception.reason);
        } catch (Exception exception) {
            return TelemetrySnapshot.unavailable(queriedAt, "QUERY_FAILED");
        }
    }

    private JsonNode queryPrometheus(String query, Instant evaluationTime) throws Exception {
        var endpoint = prometheusBaseUrl + "/api/v1/query?query=" + URLEncoder.encode(query, StandardCharsets.UTF_8)
                + "&time=" + evaluationTime.getEpochSecond();
        var request = HttpRequest.newBuilder(URI.create(endpoint)).timeout(Duration.ofSeconds(4)).GET().build();
        var response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
        try (InputStream body = response.body()) {
            var bytes = body.readNBytes(MAX_RESPONSE_BYTES + 1);
            if (bytes.length > MAX_RESPONSE_BYTES) {
                throw new QueryFailedException("RESPONSE_TOO_LARGE");
            }
            if (response.statusCode() != 200) {
                throw new QueryFailedException("QUERY_FAILED");
            }
            var parsed = objectMapper.readTree(bytes);
            var results = parsed.path("data").path("result");
            if (!"success".equals(parsed.path("status").asText()) || !results.isArray() || results.size() > MAX_SAMPLES) {
                throw new QueryFailedException("QUERY_FAILED");
            }
            return results;
        }
    }

    public enum HealthState { UP, DOWN, UNKNOWN }

    public record CatalogView(int version, Instant compiledAt, String sourceDigest, List<ServiceView> services,
            Instant queriedAt) { }

    public record ServiceView(String id, String displayName, String environment, String otelServiceName,
            String diagnosisProfile, String grafanaUrl, HealthState status, HealthState applicationStatus,
            List<ProbeView> dependencies, Instant queriedAt, Instant sampledAt, String freshness, String reason) { }

    public record ProbeView(String id, String kind, HealthState status, Instant observedAt, Instant queriedAt,
            Instant sampledAt, String freshness, String reason) { }

    record SignalKey(String metric, String job, String serviceName, String component) { }

    record InstanceKey(String metric, Map<String, String> labels) {
        InstanceKey {
            labels = Map.copyOf(new TreeMap<>(labels));
        }

        SignalKey signal() {
            return new SignalKey(metric, labels.getOrDefault("job", ""), labels.getOrDefault("service_name", ""),
                    labels.getOrDefault("component", ""));
        }
    }

    record SignalObservation(HealthState state, Instant sampledAt, String freshness, String reason) {
        static SignalObservation missing() {
            return new SignalObservation(HealthState.UNKNOWN, null, "UNKNOWN", "MISSING_SAMPLE");
        }

        static SignalObservation unavailable(String reason) {
            return new SignalObservation(HealthState.UNKNOWN, null, "UNKNOWN", reason);
        }

        static SignalObservation aggregate(List<SignalObservation> members) {
            if (members.isEmpty()) {
                return missing();
            }
            Instant oldest = null;
            for (var member : members) {
                if (member.state() != HealthState.UP && member.state() != HealthState.DOWN) {
                    return new SignalObservation(HealthState.UNKNOWN, firstSampledAt(members),
                            member.freshness(), member.reason());
                }
                if (oldest == null || (member.sampledAt() != null && member.sampledAt().isBefore(oldest))) {
                    oldest = member.sampledAt();
                }
            }
            var down = members.stream().anyMatch(member -> member.state() == HealthState.DOWN);
            return new SignalObservation(down ? HealthState.DOWN : HealthState.UP, oldest, "FRESH", "FRESH");
        }

        private static Instant firstSampledAt(List<SignalObservation> members) {
            Instant oldest = null;
            for (var member : members) {
                if (member.sampledAt() == null) {
                    continue;
                }
                if (oldest == null || member.sampledAt().isBefore(oldest)) {
                    oldest = member.sampledAt();
                }
            }
            return oldest;
        }
    }

    record TelemetrySnapshot(Instant queriedAt, Map<SignalKey, SignalObservation> values, String queryReason) {
        static TelemetrySnapshot unavailable(Instant queriedAt, String reason) {
            return new TelemetrySnapshot(queriedAt, Map.of(), reason);
        }

        static TelemetrySnapshot freshStates(Map<SignalKey, HealthState> states) {
            var observations = new HashMap<SignalKey, SignalObservation>();
            states.forEach((key, state) -> observations.put(key,
                    new SignalObservation(state, Instant.EPOCH, "FRESH", "FRESH")));
            return new TelemetrySnapshot(Instant.EPOCH, Map.copyOf(observations), "FRESH");
        }

        static TelemetrySnapshot fromSamples(Instant queriedAt, JsonNode valueResults, JsonNode timestampResults,
                Duration freshnessWindow) {
            var values = parseValues(valueResults);
            var timestamps = parseTimestamps(timestampResults);
            var keys = new HashSet<InstanceKey>();
            keys.addAll(values.keySet());
            keys.addAll(timestamps.keySet());
            var grouped = new HashMap<SignalKey, List<SignalObservation>>();
            for (var key : keys) {
                var value = values.get(key);
                var sampledAt = timestamps.get(key);
                var observation = classify(value, sampledAt, queriedAt, freshnessWindow);
                grouped.computeIfAbsent(key.signal(), ignored -> new ArrayList<>()).add(observation);
            }
            var aggregated = new HashMap<SignalKey, SignalObservation>();
            grouped.forEach((signal, members) -> aggregated.put(signal, SignalObservation.aggregate(members)));
            return new TelemetrySnapshot(queriedAt, Map.copyOf(aggregated), "FRESH");
        }

        HealthState state(String metric, String job, String serviceName, String component) {
            return observation(metric, job, serviceName, component).state();
        }

        SignalObservation observation(String metric, String job, String serviceName, String component) {
            if (queryReason != null && !"FRESH".equals(queryReason)) {
                return SignalObservation.unavailable(queryReason);
            }
            return values.getOrDefault(new SignalKey(metric, job, serviceName, component), SignalObservation.missing());
        }
    }

    static SignalObservation classify(Double value, Instant sampledAt, Instant queriedAt, Duration freshnessWindow) {
        if (value == null || sampledAt == null) {
            return new SignalObservation(HealthState.UNKNOWN, sampledAt, "UNKNOWN", "JOIN_MISMATCH");
        }
        if (!Double.isFinite(value)) {
            return new SignalObservation(HealthState.UNKNOWN, sampledAt, "UNKNOWN", "NON_FINITE_VALUE");
        }
        if (sampledAt.isAfter(queriedAt.plus(FUTURE_SKEW))) {
            return new SignalObservation(HealthState.UNKNOWN, sampledAt, "UNKNOWN", "FUTURE_SAMPLE");
        }
        if (sampledAt.isBefore(queriedAt.minus(freshnessWindow))) {
            return new SignalObservation(HealthState.UNKNOWN, sampledAt, "STALE", "STALE");
        }
        var state = value >= 0.5 ? HealthState.UP : HealthState.DOWN;
        return new SignalObservation(state, sampledAt, "FRESH", "FRESH");
    }

    private static Map<InstanceKey, Double> parseValues(JsonNode results) {
        var values = new HashMap<InstanceKey, Double>();
        for (var result : results) {
            var metric = result.path("metric");
            var key = instanceKey(metric.path("__name__").asText(""), metric);
            var parsed = parseNumber(result.path("value").path(1).asText("NaN"));
            values.put(key, parsed == null ? Double.NaN : parsed);
        }
        return values;
    }

    private static Map<InstanceKey, Instant> parseTimestamps(JsonNode results) {
        var timestamps = new HashMap<InstanceKey, Instant>();
        for (var result : results) {
            var metric = result.path("metric");
            var key = instanceKey(metric.path("metric_name").asText(""), metric);
            var parsed = parseNumber(result.path("value").path(1).asText("NaN"));
            if (parsed == null || !Double.isFinite(parsed) || parsed < 0) {
                continue;
            }
            timestamps.put(key, Instant.ofEpochMilli(Math.round(parsed * 1000.0d)));
        }
        return timestamps;
    }

    private static InstanceKey instanceKey(String metricName, JsonNode metric) {
        var labels = new TreeMap<String, String>();
        metric.fields().forEachRemaining(entry -> {
            var name = entry.getKey();
            if ("__name__".equals(name) || "metric_name".equals(name)) {
                return;
            }
            labels.put(name, entry.getValue().asText(""));
        });
        return new InstanceKey(metricName, labels);
    }

    private static Double parseNumber(String text) {
        if ("+Inf".equals(text) || "Inf".equals(text)) {
            return Double.POSITIVE_INFINITY;
        }
        if ("-Inf".equals(text)) {
            return Double.NEGATIVE_INFINITY;
        }
        if ("NaN".equals(text)) {
            return Double.NaN;
        }
        try {
            return Double.parseDouble(text);
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    static final class QueryFailedException extends Exception {
        private static final long serialVersionUID = 1L;
        final String reason;

        QueryFailedException(String reason) {
            super(reason);
            this.reason = reason;
        }
    }
}
