package com.dduo.aiops.alerts;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Reconciles restart gaps against Alertmanager's active-alert view. Mutations are separately gated because an absent
 * alert is not sufficient evidence unless the source was reachable for several consecutive observations.
 */
@Component
@ConditionalOnProperty(prefix = "aidiag.reconciliation", name = "enabled", havingValue = "true")
public class AlertStateReconciliationWorker {

    private static final int MAX_RESPONSE_BYTES = 256 * 1024;
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z0-9._-]+");
    private static final Set<String> SOURCE_CODES = Set.of("SOURCE_UNAVAILABLE", "SOURCE_HTTP_FAILED",
            "SOURCE_INVALID_SHAPE", "SOURCE_INVALID_ENVIRONMENT", "SOURCE_INTERRUPTED");

    private final JdbcTemplate jdbcTemplate;
    private final AlertmanagerWebhookService webhookService;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final URI alertsEndpoint;
    private final boolean applyTransitions;
    private final int requiredObservations;
    private final Duration grace;
    private final int maximumAttempts;
    private final Clock clock;
    private final TransactionTemplate transactionTemplate;
    private final String fallbackEnvironment;
    private final Counter sourceFailures;
    private final Counter incidentQueryFailures;
    private final Counter incidentFailures;
    private final Counter driftObservations;
    private final Counter recoveredFirings;
    private final Counter rejectedRecoveries;

    public AlertStateReconciliationWorker(JdbcTemplate jdbcTemplate, AlertmanagerWebhookService webhookService,
            ObjectMapper objectMapper, Environment environment, MeterRegistry meterRegistry, Clock clock,
            TransactionTemplate transactionTemplate) {
        this.jdbcTemplate = jdbcTemplate;
        this.webhookService = webhookService;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
        var baseUrl = environment.getProperty("aidiag.reconciliation.alertmanager-base-url", "http://alertmanager:9093");
        if (!baseUrl.matches("https?://alertmanager(?::[0-9]{1,5})?/?")) {
            throw new IllegalArgumentException("Reconciliation Alertmanager URL must use the reviewed internal host");
        }
        this.alertsEndpoint = URI.create((baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl)
                + "/api/v2/alerts");
        this.applyTransitions = environment.getProperty("aidiag.reconciliation.apply-transitions", Boolean.class, false);
        this.requiredObservations = bounded(environment, "aidiag.reconciliation.required-observations", 3, 2, 12);
        this.grace = Duration.ofMinutes(bounded(environment, "aidiag.reconciliation.grace-minutes", 10, 5, 120));
        this.maximumAttempts = bounded(environment, "aidiag.reconciliation.maximum-diagnosis-attempts", 3, 1, 5);
        this.clock = clock;
        this.transactionTemplate = transactionTemplate;
        this.fallbackEnvironment = requiredIdentifier(environment.getProperty("DEPLOYMENT_ENVIRONMENT", "local"), 64,
                "environment");
        this.sourceFailures = meterRegistry.counter("aidiag.reconciliation.runs", "outcome", "source_unavailable");
        this.incidentQueryFailures = meterRegistry.counter("aidiag.reconciliation.runs", "outcome", "incident_query_failed");
        this.incidentFailures = meterRegistry.counter("aidiag.reconciliation.runs", "outcome", "incident_failed");
        this.driftObservations = meterRegistry.counter("aidiag.reconciliation.runs", "outcome", "drift");
        this.recoveredFirings = meterRegistry.counter("aidiag.reconciliation.runs", "outcome", "firing_recovered");
        this.rejectedRecoveries = meterRegistry.counter("aidiag.reconciliation.runs", "outcome", "recovery_rejected");
    }

    @Scheduled(initialDelayString = "${aidiag.reconciliation.initial-delay-ms:60000}",
            fixedDelayString = "${aidiag.reconciliation.delay-ms:60000}")
    public void reconcile() {
        final ActiveAlerts activeAlerts;
        try {
            activeAlerts = fetchActiveAlerts();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            recordSourceFailure("SOURCE_INTERRUPTED");
            return;
        } catch (Exception exception) {
            recordSourceFailure(sourceFailureCode(exception));
            return;
        }
        final List<IncidentIdentity> incidents;
        try {
            incidents = activeIncidents();
        } catch (RuntimeException exception) {
            incidentQueryFailures.increment();
            return;
        }
        observeIncidents(incidents, activeAlerts.identities());
        if (applyTransitions) {
            recoverMissingFirings(activeAlerts.documents(), incidents);
        }
    }

    private ActiveAlerts fetchActiveAlerts() throws Exception {
        var request = HttpRequest.newBuilder(alertsEndpoint).timeout(Duration.ofSeconds(4)).GET().build();
        var response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
        try (InputStream body = response.body()) {
            var bytes = body.readNBytes(MAX_RESPONSE_BYTES + 1);
            if (response.statusCode() != 200 || bytes.length > MAX_RESPONSE_BYTES) {
                throw new SourceQueryException("SOURCE_HTTP_FAILED");
            }
            var parsed = objectMapper.readTree(bytes);
            if (!parsed.isArray() || parsed.size() > 10_000) {
                throw new SourceQueryException("SOURCE_INVALID_SHAPE");
            }
            return parseActiveAlerts(parsed);
        }
    }

    ActiveAlerts parseActiveAlerts(JsonNode parsed) {
        var active = new HashSet<AlertIdentity>();
        var documents = new java.util.ArrayList<JsonNode>();
        for (var alert : parsed) {
            var labels = alert.path("labels");
            var alertName = labels.path("alertname").asText("");
            var service = labels.path("service_name").asText("");
            if (!validIdentifier(alertName, 128) || !validIdentifier(service, 128)) {
                continue;
            }
            var environment = resolveEnvironment(labels.path("environment").asText(""));
            active.add(new AlertIdentity(alertName, service, environment));
            documents.add(alert.deepCopy());
        }
        return new ActiveAlerts(Set.copyOf(active), List.copyOf(documents));
    }

    private List<IncidentIdentity> activeIncidents() {
        return jdbcTemplate.query("""
                SELECT id, alert_name, primary_service, environment, status, last_alert_at
                FROM incident WHERE status IN ('OPEN','DIAGNOSING','DIAGNOSED','FAILED')
                ORDER BY last_alert_at DESC LIMIT 10000
                """, (rs, row) -> new IncidentIdentity(rs.getObject("id", UUID.class), rs.getString("alert_name"),
                        rs.getString("primary_service"), rs.getString("environment"), rs.getString("status"),
                        rs.getTimestamp("last_alert_at").toInstant()));
    }

    void observeIncidents(List<IncidentIdentity> incidents, Set<AlertIdentity> activeAlerts) {
        for (var incident : incidents) {
            try {
                transactionTemplate.executeWithoutResult(status -> reconcileIncident(incident, activeAlerts));
            } catch (RuntimeException exception) {
                incidentFailures.increment();
                try {
                    recordIncidentFailure(incident.id());
                } catch (RuntimeException ignored) {
                    // A single incident failure must not stop the remaining observations.
                }
            }
        }
    }

    void recoverMissingFirings(List<JsonNode> activeAlertDocuments, List<IncidentIdentity> incidents) {
        var known = new HashSet<AlertIdentity>();
        incidents.forEach(incident -> known.add(incident.identity()));
        for (var alert : activeAlertDocuments) {
            var labels = alert.path("labels");
            AlertIdentity identity;
            try {
                identity = new AlertIdentity(labels.path("alertname").asText(""),
                        labels.path("service_name").asText(""), resolveEnvironment(labels.path("environment").asText("")));
            } catch (RuntimeException exception) {
                rejectedRecoveries.increment();
                continue;
            }
            if (known.contains(identity)) {
                continue;
            }
            try {
                var normalized = objectMapper.createObjectNode();
                normalized.put("status", "firing");
                normalized.set("labels", labels.deepCopy());
                normalized.set("annotations", alert.path("annotations").isObject()
                        ? alert.path("annotations").deepCopy() : objectMapper.createObjectNode());
                normalized.put("startsAt", alert.path("startsAt").asText(""));
                normalized.put("endsAt", alert.path("endsAt").asText(""));
                normalized.put("fingerprint", alert.path("fingerprint").asText(""));

                var payload = objectMapper.createObjectNode();
                payload.put("receiver", "aidiag-reconciliation");
                payload.put("status", "firing");
                payload.put("groupKey", "reconciliation:" + identity.alertName() + ":" + identity.service());
                payload.putArray("alerts").add(normalized);
                var rawBody = objectMapper.writeValueAsBytes(payload);
                webhookService.accept(payload, rawBody);
                known.add(identity);
                recoveredFirings.increment();
            } catch (RuntimeException | java.io.IOException exception) {
                rejectedRecoveries.increment();
            }
        }
    }

    void reconcileIncident(IncidentIdentity incident, Set<AlertIdentity> activeAlerts) {
        var present = activeAlerts.contains(incident.identity());
        var state = present ? "CONSISTENT" : "ALERTMANAGER_ALERT_ABSENT";
        var count = upsertObservation(incident.id(), state, null);
        if (!present) {
            driftObservations.increment();
            if (applyTransitions && count >= requiredObservations
                    && incident.lastAlertAt().isBefore(clock.instant().minus(grace))) {
                jdbcTemplate.update("""
                        UPDATE incident SET status = 'RESOLVED', resolved_at = CURRENT_TIMESTAMP,
                            updated_at = CURRENT_TIMESTAMP
                        WHERE id = ? AND status IN ('OPEN','DIAGNOSING','DIAGNOSED','FAILED')
                        """, incident.id());
            }
        } else if (applyTransitions && "FAILED".equals(incident.status())) {
            scheduleBoundedRediagnosis(incident.id());
        }
    }

    private void scheduleBoundedRediagnosis(UUID incidentId) {
        jdbcTemplate.update("""
                INSERT INTO diagnosis_run (incident_id, attempt, run_kind, status, available_at, previous_run_id)
                SELECT ?, previous.attempt + 1, 'AUTOMATIC_RETRY', 'RETRY_WAIT', CURRENT_TIMESTAMP, previous.id
                FROM diagnosis_run previous
                WHERE previous.id = (SELECT id FROM diagnosis_run WHERE incident_id = ? ORDER BY attempt DESC LIMIT 1)
                  AND previous.attempt < ?
                  AND NOT EXISTS (SELECT 1 FROM diagnosis_run active WHERE active.incident_id = ?
                      AND active.status IN ('PENDING','LEASED','COLLECTING','EVIDENCE_READY','ANALYZING','VERIFYING','GENERATING_REPORT','RETRY_WAIT'))
                ON CONFLICT (incident_id, attempt) DO NOTHING
                """, incidentId, incidentId, maximumAttempts, incidentId);
    }

    private void recordIncidentFailure(UUID incidentId) {
        jdbcTemplate.update("""
                INSERT INTO alert_state_reconciliation
                    (incident_id, observed_state, consecutive_observations, first_observed_at, last_observed_at, last_error_code)
                VALUES (?, 'INCIDENT_RECONCILE_FAILED', 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'INCIDENT_RECONCILE_FAILED')
                ON CONFLICT (incident_id) DO UPDATE SET
                    last_error_code = 'INCIDENT_RECONCILE_FAILED',
                    last_observed_at = CURRENT_TIMESTAMP
                """, incidentId);
    }

    private int upsertObservation(UUID incidentId, String state, String errorCode) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO alert_state_reconciliation
                    (incident_id, observed_state, consecutive_observations, first_observed_at, last_observed_at, last_error_code)
                VALUES (?, ?, 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, ?)
                ON CONFLICT (incident_id) DO UPDATE SET
                    consecutive_observations = CASE
                        WHEN alert_state_reconciliation.observed_state = EXCLUDED.observed_state
                        THEN alert_state_reconciliation.consecutive_observations + 1 ELSE 1 END,
                    first_observed_at = CASE
                        WHEN alert_state_reconciliation.observed_state = EXCLUDED.observed_state
                        THEN alert_state_reconciliation.first_observed_at ELSE CURRENT_TIMESTAMP END,
                    observed_state = EXCLUDED.observed_state,
                    last_observed_at = CURRENT_TIMESTAMP,
                    last_error_code = EXCLUDED.last_error_code
                RETURNING consecutive_observations
                """, Integer.class, incidentId, state, errorCode);
    }

    private void recordSourceFailure(String errorCode) {
        sourceFailures.increment();
        try {
            markSourceUnavailable(errorCode);
        } catch (RuntimeException ignored) {
            // Source failure is already counted; a follow-up write must not raise.
        }
    }

    private void markSourceUnavailable(String errorCode) {
        jdbcTemplate.update("""
                INSERT INTO alert_state_reconciliation
                    (incident_id, observed_state, consecutive_observations, first_observed_at, last_observed_at, last_error_code)
                SELECT id, 'SOURCE_UNAVAILABLE', 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, ?
                FROM incident WHERE status IN ('OPEN','DIAGNOSING','DIAGNOSED','FAILED')
                ON CONFLICT (incident_id) DO UPDATE SET observed_state = 'SOURCE_UNAVAILABLE',
                    consecutive_observations = 1, first_observed_at = CURRENT_TIMESTAMP,
                    last_observed_at = CURRENT_TIMESTAMP, last_error_code = EXCLUDED.last_error_code
                """, errorCode);
    }

    private String resolveEnvironment(String raw) {
        var value = raw == null ? "" : raw.trim();
        if (value.isEmpty()) {
            return fallbackEnvironment;
        }
        if (!validIdentifier(value, 64)) {
            throw new SourceQueryException("SOURCE_INVALID_ENVIRONMENT");
        }
        return value;
    }

    private static String sourceFailureCode(Exception exception) {
        if (exception instanceof InterruptedException) {
            return "SOURCE_INTERRUPTED";
        }
        if (exception instanceof SourceQueryException source) {
            return source.code;
        }
        var message = exception.getMessage();
        return message != null && SOURCE_CODES.contains(message) ? message : "SOURCE_UNAVAILABLE";
    }

    private static boolean validIdentifier(String value, int maximumLength) {
        return value != null && !value.isBlank() && value.length() <= maximumLength && IDENTIFIER.matcher(value).matches();
    }

    private static String requiredIdentifier(String value, int maximumLength, String field) {
        if (!validIdentifier(value, maximumLength)) {
            throw new IllegalArgumentException("invalid " + field);
        }
        return value;
    }

    private static int bounded(Environment environment, String property, int fallback, int minimum, int maximum) {
        var value = environment.getProperty(property, Integer.class, fallback);
        if (value < minimum || value > maximum) {
            throw new IllegalArgumentException(property + " is outside the reviewed bounds");
        }
        return value;
    }

    record AlertIdentity(String alertName, String service, String environment) { }

    record IncidentIdentity(UUID id, String alertName, String service, String environment, String status,
            Instant lastAlertAt) {
        AlertIdentity identity() {
            return new AlertIdentity(alertName, service, environment);
        }
    }

    record ActiveAlerts(Set<AlertIdentity> identities, List<JsonNode> documents) { }

    static final class SourceQueryException extends RuntimeException {
        private static final long serialVersionUID = 1L;
        final String code;

        SourceQueryException(String code) {
            super(code);
            this.code = code;
        }
    }
}
