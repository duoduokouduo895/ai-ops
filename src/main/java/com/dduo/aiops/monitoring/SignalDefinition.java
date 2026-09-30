package com.dduo.aiops.monitoring;

/**
 * One server-owned monitoring signal. {@code queryTemplate} contains a single {@code %s} placeholder for the
 * catalog-validated service_name label; nothing else from the request is interpolated.
 *
 * @param splitLabel label that separates series (for example {@code outcome}); {@code null} for a single series
 * @param threshold  alert threshold kept in sync with {@code infra/prometheus/rules}; {@code null} if not alerted
 * @param alertName  Prometheus alert that watches the same signal; {@code null} if not alerted
 */
public record SignalDefinition(String id, String group, String title, String unit, String description,
        String queryTemplate, String splitLabel, Double threshold, String alertName) {

    String query(String serviceName) {
        return queryTemplate.formatted(serviceName);
    }
}
