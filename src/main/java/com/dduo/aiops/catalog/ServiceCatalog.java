package com.dduo.aiops.catalog;

import java.time.Instant;
import java.util.List;

public record ServiceCatalog(int version, List<MonitoredService> services) {

    public record MonitoredService(String id, String displayName, String environment, String healthUrl,
            String metricsUrl, String otelServiceName, String diagnosisProfile, String grafanaUrl,
            List<DependencyProbe> dependencies) {
    }

    public record DependencyProbe(String id, String kind, String target, String signalComponent) {
    }

    public record Snapshot(int version, Instant compiledAt, String sourceDigest, List<MonitoredService> services) {
    }
}
