package com.dduo.aiops.monitoring;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.regex.Pattern;

import com.dduo.aiops.catalog.ServiceCatalog;
import com.dduo.aiops.catalog.ServiceCatalogRegistry;
import com.dduo.aiops.telemetry.PrometheusClient;
import org.springframework.stereotype.Service;

/**
 * Serves fixed golden-signal time series for one catalog service. The browser chooses only the service id and one
 * of three ranges; PromQL, step and series limits are owned here. Results are cached briefly so refresh spam cannot
 * multiply Prometheus load.
 */
@Service
public class ServiceSignalsService {

    static final int MAX_SERIES_PER_SIGNAL = 6;
    static final Duration CACHE_TTL = Duration.ofSeconds(15);
    private static final Pattern SAFE_LABEL = Pattern.compile("[A-Za-z0-9._-]{1,128}");

    private final ServiceCatalogRegistry registry;
    private final PrometheusClient prometheus;
    private final Clock clock;
    private final Map<String, SignalsView> cache = new ConcurrentHashMap<>();

    public ServiceSignalsService(ServiceCatalogRegistry registry, PrometheusClient prometheus, Clock clock) {
        this.registry = registry;
        this.prometheus = prometheus;
        this.clock = clock;
    }

    public Optional<SignalsView> signals(String serviceId, SignalRange range) {
        var service = registry.snapshot().services().stream().filter(item -> item.id().equals(serviceId)).findFirst();
        if (service.isEmpty()) {
            return Optional.empty();
        }
        var definitions = SignalProfiles.forProfile(service.get().diagnosisProfile());
        if (definitions.isEmpty() || !SAFE_LABEL.matcher(service.get().otelServiceName()).matches()) {
            return Optional.empty();
        }
        var now = clock.instant();
        var key = serviceId + "|" + range.code();
        var cached = cache.get(key);
        if (cached != null && cached.queriedAt().plus(CACHE_TTL).isAfter(now)) {
            return Optional.of(cached);
        }
        var view = query(service.get(), definitions, range, now);
        cache.put(key, view);
        return Optional.of(view);
    }

    SignalsView query(ServiceCatalog.MonitoredService service, List<SignalDefinition> definitions, SignalRange range,
            Instant now) {
        var end = Instant.ofEpochSecond(now.getEpochSecond() - now.getEpochSecond() % range.step().toSeconds());
        var start = end.minus(range.window());
        var serviceName = service.otelServiceName();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<AlertStates> alerts = executor.submit(() -> alertStates(serviceName, now));
            var futures = new ArrayList<Future<SignalView>>();
            for (var definition : definitions) {
                futures.add(executor.submit(() -> signal(definition, serviceName, start, end, range.step())));
            }
            var alertStates = alerts.get();
            var signals = new ArrayList<SignalView>();
            for (var future : futures) {
                var signal = future.get();
                signals.add(signal.withAlertState(alertStates.stateOf(signal.alertName())));
            }
            return new SignalsView(service.id(), service.displayName(), service.environment(), serviceName,
                    range.code(), start, end, range.step().toSeconds(), now, List.copyOf(signals));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("signal query interrupted", exception);
        } catch (Exception exception) {
            throw new IllegalStateException("signal query failed", exception);
        }
    }

    private SignalView signal(SignalDefinition definition, String serviceName, Instant start, Instant end,
            Duration step) {
        try {
            var raw = prometheus.range(definition.query(serviceName), start, end, step);
            var series = raw.stream()
                    .map(item -> new SeriesView(seriesLabel(definition, item.labels()), item.points()))
                    .sorted(Comparator.comparing(SeriesView::label))
                    .limit(MAX_SERIES_PER_SIGNAL)
                    .toList();
            var current = latest(series);
            String status;
            if (current == null) {
                status = "NO_DATA";
            } else if (definition.threshold() != null && current >= definition.threshold()) {
                status = "BREACH";
            } else if ("availability".equals(definition.id()) && current < 0.5) {
                status = "BREACH";
            } else {
                status = "OK";
            }
            return SignalView.of(definition, status, null, current, series);
        } catch (PrometheusClient.QueryException exception) {
            return SignalView.of(definition, "QUERY_FAILED", exception.reason(), null, List.of());
        }
    }

    private AlertStates alertStates(String serviceName, Instant now) {
        try {
            var samples = prometheus.instant("ALERTS{service_name=\"" + serviceName + "\"}", now);
            var states = new HashMap<String, String>();
            for (var sample : samples) {
                var name = sample.labels().getOrDefault("alertname", "");
                var state = "firing".equals(sample.labels().get("alertstate")) ? "FIRING" : "PENDING";
                states.merge(name, state, (left, right) -> "FIRING".equals(left) ? left : right);
            }
            return new AlertStates(true, Map.copyOf(states));
        } catch (PrometheusClient.QueryException exception) {
            return new AlertStates(false, Map.of());
        }
    }

    private static String seriesLabel(SignalDefinition definition, Map<String, String> labels) {
        if (definition.splitLabel() == null) {
            return definition.id();
        }
        var value = labels.getOrDefault(definition.splitLabel(), "");
        return SAFE_LABEL.matcher(value).matches() ? value : "other";
    }

    /** Latest value across series: the maximum of the final points, which is what threshold rules compare. */
    private static Double latest(List<SeriesView> series) {
        Double latest = null;
        long latestTime = Long.MIN_VALUE;
        for (var item : series) {
            if (item.points().isEmpty()) {
                continue;
            }
            var point = item.points().getLast();
            if (point.t() > latestTime) {
                latestTime = point.t();
                latest = point.v();
            } else if (point.t() == latestTime) {
                latest = Math.max(latest, point.v());
            }
        }
        return latest;
    }

    record AlertStates(boolean available, Map<String, String> states) {
        String stateOf(String alertName) {
            if (alertName == null) {
                return null;
            }
            return available ? states.getOrDefault(alertName, "INACTIVE") : "UNKNOWN";
        }
    }

    public record SignalsView(String serviceId, String displayName, String environment, String otelServiceName,
            String range, Instant start, Instant end, long stepSeconds, Instant queriedAt, List<SignalView> signals) { }

    public record SignalView(String id, String group, String title, String unit, String description, Double threshold,
            String alertName, String alertState, String status, String reason, Double current,
            List<SeriesView> series) {

        static SignalView of(SignalDefinition definition, String status, String reason, Double current,
                List<SeriesView> series) {
            return new SignalView(definition.id(), definition.group(), definition.title(), definition.unit(),
                    definition.description(), definition.threshold(), definition.alertName(), null, status, reason,
                    current, series);
        }

        SignalView withAlertState(String state) {
            return new SignalView(id, group, title, unit, description, threshold, alertName, state, status, reason,
                    current, series);
        }
    }

    public record SeriesView(String label, List<PrometheusClient.Point> points) { }
}
