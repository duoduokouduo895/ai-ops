package com.dduo.aiops.alerts;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Persists one Alertmanager group atomically while treating each alert as an independent event. */
@Service
public class AlertmanagerWebhookService {

    private static final Logger log = LoggerFactory.getLogger(AlertmanagerWebhookService.class);
    private static final Pattern CONTROLLED_IDENTIFIER = Pattern.compile("[A-Za-z0-9._-]+");
    private static final Pattern SENSITIVE_FIELD = Pattern.compile(
            "(?i)(password|passwd|token|api[_-]?key|secret|authorization|cookie|session)");
    private static final Pattern BEARER_SECRET = Pattern.compile("(?i)\\bBearer\\s+[A-Za-z0-9._~+/-]+=*");
    private static final Pattern ASSIGNED_SECRET = Pattern.compile(
            "(?i)\\b(password|passwd|token|api[_-]?key|secret|authorization|cookie|session)\\b\\s*[:=]\\s*[^\\s,;]+");
    private static final Pattern STANDALONE_API_KEY = Pattern.compile("(?i)\\bsk-[A-Za-z0-9_-]{16,}\\b");
    private static final Pattern URI_USER_INFO = Pattern.compile("(?i)\\b(https?://)[^\\s/@:]+:[^\\s/@]+@");
    private static final Pattern EMAIL = Pattern.compile("(?i)\\b[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}\\b");
    private static final Pattern CN_MOBILE = Pattern.compile("(?<![A-Za-z0-9])(?:\\+?86[- ]?)?1[3-9]\\d{9}(?!\\d)");

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final String fallbackEnvironment;
    private final Counter acceptedEvents;
    private final Counter duplicateEvents;
    private final Counter truncatedGroups;

    public AlertmanagerWebhookService(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper, Environment environment,
            MeterRegistry meterRegistry) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.fallbackEnvironment = environment.getProperty("DEPLOYMENT_ENVIRONMENT", "local");
        this.acceptedEvents = meterRegistry.counter("aidiag.alert.webhook.events", "outcome", "accepted");
        this.duplicateEvents = meterRegistry.counter("aidiag.alert.webhook.events", "outcome", "duplicate");
        this.truncatedGroups = meterRegistry.counter("aidiag.alert.webhook.groups", "outcome", "truncated");
    }

    @Transactional
    public WebhookReceipt accept(JsonNode payload, byte[] rawBody) {
        var receiver = requiredText(payload, "receiver", 128);
        var groupKey = requiredText(payload, "groupKey", 512);
        var alerts = payload.path("alerts");
        if (!alerts.isArray() || alerts.isEmpty()) {
            throw new IllegalArgumentException("alerts must contain at least one entry");
        }
        if (payload.path("truncatedAlerts").asInt(0) > 0) {
            truncatedGroups.increment();
            log.atWarn().addKeyValue("event", "aidiag_alertmanager_group_truncated").addKeyValue("receiver", receiver)
                    .addKeyValue("groupKeyHash", sha256(groupKey)).log("Alertmanager webhook group was truncated");
        }

        var payloadHash = sha256(rawBody);
        var groupResolved = isResolvedGroup(payload, alerts);
        var accepted = 0;
        var duplicates = 0;
        var incidents = new ArrayList<UUID>();
        var resolutionTimes = new LinkedHashMap<String, Instant>();
        for (JsonNode alert : alerts) {
            var event = parseAlert(alert, receiver, groupKey, payload, payloadHash);
            if (groupResolved) {
                var resolutionTime = event.endsAt() == null ? event.startsAt() : event.endsAt();
                resolutionTimes.merge(event.correlationKey(), resolutionTime,
                        (left, right) -> left.isAfter(right) ? left : right);
            }
            var eventId = insertEvent(event);
            if (eventId == null) {
                incrementDuplicate(event);
                duplicates++;
                continue;
            }
            accepted++;
            acceptedEvents.increment();
            if (event.status().equals("firing")) {
                var incident = upsertActiveIncident(event);
                linkEvent(incident, eventId);
                jdbcTemplate.update("""
                        INSERT INTO incident_affected_service (incident_id, service_name, impact_role)
                        VALUES (?, ?, 'SYMPTOM')
                        ON CONFLICT (incident_id, service_name) DO NOTHING
                        """, incident, event.serviceName());
                ensureInitialRun(incident);
                incidents.add(incident);
            } else {
                findActiveIncident(event.correlationKey()).ifPresent(incident -> {
                    linkEvent(incident, eventId);
                    jdbcTemplate.update("""
                            UPDATE incident
                            SET last_alert_at = GREATEST(last_alert_at, ?), updated_at = CURRENT_TIMESTAMP
                            WHERE id = ?
                            """, timestamp(event.endsAt() == null ? event.startsAt() : event.endsAt()), incident);
                    incidents.add(incident);
                });
            }
        }
        if (groupResolved) {
            resolutionTimes.forEach((correlationKey, resolvedAt) -> findActiveIncident(correlationKey).ifPresent(incident -> {
                var updated = jdbcTemplate.update("""
                        UPDATE incident
                        SET status = 'RESOLVED', resolved_at = ?, last_alert_at = GREATEST(last_alert_at, ?),
                            updated_at = CURRENT_TIMESTAMP
                        WHERE id = ? AND status IN ('OPEN', 'DIAGNOSING', 'DIAGNOSED', 'FAILED')
                          AND last_alert_at <= ?
                        """, timestamp(resolvedAt), timestamp(resolvedAt), incident, timestamp(resolvedAt));
                if (updated == 1) {
                    incidents.add(incident);
                }
            }));
        }
        return new WebhookReceipt(accepted, duplicates, incidents.stream().distinct().toList());
    }

    private AlertEvent parseAlert(JsonNode alert, String receiver, String groupKey, JsonNode payload, String payloadHash) {
        var status = requiredText(alert, "status", 16).toLowerCase(Locale.ROOT);
        if (!status.equals("firing") && !status.equals("resolved")) {
            throw new IllegalArgumentException("unsupported alert status");
        }
        var labels = requiredObject(alert, "labels");
        var annotations = alert.path("annotations").isObject() ? alert.path("annotations") : objectMapper.createObjectNode();
        var alertName = requiredIdentifier(labels, "alertname", 128);
        var serviceName = requiredIdentifier(labels, "service_name", 128);
        var severity = requiredText(labels, "severity", 32).toUpperCase(Locale.ROOT);
        if (!List.of("INFO", "WARNING", "CRITICAL").contains(severity)) {
            throw new IllegalArgumentException("unsupported severity");
        }
        var environment = requiredIdentifier(labels, "environment", 64, fallbackEnvironment);
        var startsAt = parseTimestamp(requiredText(alert, "startsAt", 64), "startsAt");
        var endsAt = parseOptionalEnd(alert.path("endsAt").asText(""), startsAt);
        var fingerprint = requiredText(alert, "fingerprint", 128);
        return new AlertEvent(receiver, groupKey, fingerprint, status, startsAt, endsAt, toJson(sanitizeForStorage(labels)),
                toJson(sanitizeForStorage(annotations)), toJson(sanitizeForStorage(payload)), payloadHash,
                sha256(environment + "\n" + receiver + "\n" + groupKey), alertName, serviceName, severity, environment);
    }

    private UUID insertEvent(AlertEvent event) {
        var ids = jdbcTemplate.query("""
                INSERT INTO alert_event (receiver, group_key, fingerprint, alert_status, starts_at, ends_at, labels, annotations, raw_payload, payload_hash)
                VALUES (?, ?, ?, ?, ?, ?, CAST(? AS jsonb), CAST(? AS jsonb), CAST(? AS jsonb), ?)
                ON CONFLICT (receiver, group_key, fingerprint, alert_status, starts_at) DO NOTHING
                RETURNING id
                """, (resultSet, rowNum) -> UUID.fromString(resultSet.getString("id")), event.receiver(), event.groupKey(),
                event.fingerprint(), event.status(), timestamp(event.startsAt()), timestamp(event.endsAt()), event.labelsJson(), event.annotationsJson(),
                event.rawPayloadJson(), event.payloadHash());
        return ids.isEmpty() ? null : ids.get(0);
    }

    private void incrementDuplicate(AlertEvent event) {
        jdbcTemplate.update("""
                UPDATE alert_event
                SET receive_count = receive_count + 1, last_received_at = CURRENT_TIMESTAMP
                WHERE receiver = ? AND group_key = ? AND fingerprint = ? AND alert_status = ? AND starts_at = ?
                """, event.receiver(), event.groupKey(), event.fingerprint(), event.status(), timestamp(event.startsAt()));
        duplicateEvents.increment();
    }

    private UUID upsertActiveIncident(AlertEvent event) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO incident (title, primary_service, alert_name, severity, status, started_at, detected_at,
                                      correlation_key, environment, last_alert_at)
                VALUES (?, ?, ?, ?, 'OPEN', ?, CURRENT_TIMESTAMP, ?, ?, ?)
                ON CONFLICT (correlation_key) WHERE status IN ('OPEN', 'DIAGNOSING', 'DIAGNOSED', 'FAILED')
                DO UPDATE SET last_alert_at = GREATEST(incident.last_alert_at, EXCLUDED.last_alert_at),
                    severity = CASE
                        WHEN incident.severity = 'CRITICAL' OR EXCLUDED.severity = 'CRITICAL' THEN 'CRITICAL'
                        WHEN incident.severity = 'WARNING' OR EXCLUDED.severity = 'WARNING' THEN 'WARNING'
                        ELSE 'INFO'
                    END,
                    updated_at = CURRENT_TIMESTAMP
                RETURNING id
                """, (resultSet, rowNum) -> UUID.fromString(resultSet.getString("id")), event.alertName() + " on " + event.serviceName(),
                event.serviceName(), event.alertName(), event.severity(), timestamp(event.startsAt()), event.correlationKey(),
                event.environment(), timestamp(event.startsAt()));
    }

    private void linkEvent(UUID incidentId, UUID eventId) {
        jdbcTemplate.update("INSERT INTO incident_alert (incident_id, alert_event_id) VALUES (?, ?) ON CONFLICT DO NOTHING",
                incidentId, eventId);
    }

    private void ensureInitialRun(UUID incidentId) {
        jdbcTemplate.update("""
                INSERT INTO diagnosis_run (incident_id, attempt, run_kind, status)
                VALUES (?, 1, 'INITIAL', 'PENDING')
                ON CONFLICT (incident_id, attempt) DO NOTHING
                """, incidentId);
    }

    private java.util.Optional<UUID> findActiveIncident(String correlationKey) {
        var result = jdbcTemplate.query("""
                SELECT id FROM incident
                WHERE correlation_key = ? AND status IN ('OPEN', 'DIAGNOSING', 'DIAGNOSED', 'FAILED')
                ORDER BY created_at DESC LIMIT 1
                """, (resultSet, rowNum) -> UUID.fromString(resultSet.getString("id")), correlationKey);
        return result.stream().findFirst();
    }

    private static String requiredText(JsonNode node, String field, int maximumLength) {
        return requiredText(node, field, maximumLength, "");
    }

    private static String requiredText(JsonNode node, String field, int maximumLength, String fallback) {
        var value = node.path(field).asText(fallback).trim();
        if (value.isBlank() || value.length() > maximumLength) {
            throw new IllegalArgumentException("missing or oversized " + field);
        }
        return value;
    }

    private static String requiredIdentifier(JsonNode node, String field, int maximumLength) {
        return requiredIdentifier(node, field, maximumLength, "");
    }

    private static String requiredIdentifier(JsonNode node, String field, int maximumLength, String fallback) {
        var value = requiredText(node, field, maximumLength, fallback);
        if (!CONTROLLED_IDENTIFIER.matcher(value).matches()) {
            throw new IllegalArgumentException("invalid " + field);
        }
        return value;
    }

    JsonNode sanitizeForStorage(JsonNode value) {
        var copy = value.deepCopy();
        sanitizeInPlace(copy);
        return copy;
    }

    private void sanitizeInPlace(JsonNode node) {
        if (node.isObject()) {
            var fields = new ArrayList<String>();
            node.fieldNames().forEachRemaining(fields::add);
            for (var field : fields) {
                var child = node.get(field);
                if (SENSITIVE_FIELD.matcher(field).find()) {
                    ((com.fasterxml.jackson.databind.node.ObjectNode) node).put(field, "[REDACTED]");
                } else if (child.isTextual()) {
                    ((com.fasterxml.jackson.databind.node.ObjectNode) node).put(field, sanitizeText(child.asText()));
                } else {
                    sanitizeInPlace(child);
                }
            }
        } else if (node.isArray()) {
            var array = (com.fasterxml.jackson.databind.node.ArrayNode) node;
            for (var index = 0; index < array.size(); index++) {
                var child = array.get(index);
                if (child.isTextual()) {
                    array.set(index, objectMapper.getNodeFactory().textNode(sanitizeText(child.asText())));
                } else {
                    sanitizeInPlace(child);
                }
            }
        }
    }

    private static String sanitizeText(String value) {
        var sanitized = BEARER_SECRET.matcher(value).replaceAll("Bearer [REDACTED]");
        sanitized = ASSIGNED_SECRET.matcher(sanitized).replaceAll("$1=[REDACTED]");
        sanitized = STANDALONE_API_KEY.matcher(sanitized).replaceAll("[REDACTED_API_KEY]");
        sanitized = URI_USER_INFO.matcher(sanitized).replaceAll("$1[REDACTED]@");
        sanitized = EMAIL.matcher(sanitized).replaceAll("[REDACTED_EMAIL]");
        return CN_MOBILE.matcher(sanitized).replaceAll("[REDACTED_PHONE]");
    }

    private boolean isResolvedGroup(JsonNode payload, JsonNode alerts) {
        var groupStatus = payload.path("status").asText("").trim().toLowerCase(Locale.ROOT);
        if (!groupStatus.isBlank()) {
            if (!groupStatus.equals("firing") && !groupStatus.equals("resolved")) {
                throw new IllegalArgumentException("unsupported group status");
            }
            var hasFiringAlert = false;
            for (JsonNode alert : alerts) {
                var alertStatus = alert.path("status").asText("").trim().toLowerCase(Locale.ROOT);
                hasFiringAlert |= "firing".equals(alertStatus);
                if (groupStatus.equals("resolved") && !"resolved".equals(alertStatus)) {
                    throw new IllegalArgumentException("resolved group contains a non-resolved alert");
                }
            }
            if (groupStatus.equals("firing") && !hasFiringAlert) {
                throw new IllegalArgumentException("firing group contains no firing alert");
            }
            return groupStatus.equals("resolved");
        }
        for (JsonNode alert : alerts) {
            if (!"resolved".equals(alert.path("status").asText("").trim().toLowerCase(Locale.ROOT))) {
                return false;
            }
        }
        return true;
    }

    private static JsonNode requiredObject(JsonNode node, String field) {
        var value = node.path(field);
        if (!value.isObject()) {
            throw new IllegalArgumentException(field + " must be an object");
        }
        return value;
    }

    private static Instant parseTimestamp(String value, String field) {
        try {
            return Instant.parse(value);
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("invalid " + field, exception);
        }
    }

    private static Instant parseOptionalEnd(String value, Instant startsAt) {
        if (value.isBlank()) {
            return null;
        }
        var parsed = parseTimestamp(value, "endsAt");
        if (parsed.getEpochSecond() <= 0) {
            return null;
        }
        if (parsed.isBefore(startsAt)) {
            throw new IllegalArgumentException("endsAt precedes startsAt");
        }
        return parsed;
    }

    private String toJson(JsonNode node) {
        try {
            return objectMapper.writeValueAsString(node);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("payload cannot be serialized", exception);
        }
    }

    private static String sha256(String value) {
        return sha256(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static Timestamp timestamp(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }

    public record WebhookReceipt(int acceptedEvents, int duplicateEvents, List<UUID> incidentIds) {
    }

    private record AlertEvent(String receiver, String groupKey, String fingerprint, String status, Instant startsAt,
            Instant endsAt, String labelsJson, String annotationsJson, String rawPayloadJson, String payloadHash,
            String correlationKey, String alertName, String serviceName, String severity, String environment) {
    }
}
