package com.dduo.aiops.diagnosis;

import java.util.Objects;

/** A server-owned diagnosis contract. No query fragment comes from an alert payload. */
public record DiagnosisRule(String serviceName, String alertName, String metricQuery, double metricThreshold,
        String metricDescription, String logQuery, String logMarker, String traceQuery, String candidateName,
        String groundTruth, String description, String remediationJson) {

    public DiagnosisRule {
        serviceName = required(serviceName, "serviceName");
        alertName = required(alertName, "alertName");
        metricQuery = required(metricQuery, "metricQuery");
        metricDescription = required(metricDescription, "metricDescription");
        logQuery = required(logQuery, "logQuery");
        logMarker = required(logMarker, "logMarker");
        traceQuery = required(traceQuery, "traceQuery");
        candidateName = required(candidateName, "candidateName");
        groundTruth = required(groundTruth, "groundTruth");
        description = required(description, "description");
        remediationJson = required(remediationJson, "remediationJson");
        if (!Double.isFinite(metricThreshold)) {
            throw new IllegalArgumentException("metricThreshold must be finite");
        }
    }

    private static String required(String value, String field) {
        var normalized = Objects.requireNonNull(value, field).trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return normalized;
    }
}
