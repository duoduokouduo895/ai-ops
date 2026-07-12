package com.dduo.aiops.alerts;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Read-only Stage 2 API. It makes automatic Incident creation observable without inventing a report. */
@RestController
@RequestMapping("/api/v1/incidents")
public class IncidentQueryController {

    private final JdbcTemplate jdbcTemplate;

    public IncidentQueryController(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @GetMapping
    public List<IncidentSummary> list() {
        return jdbcTemplate.query("""
                SELECT id, title, primary_service, alert_name, severity, status, started_at, last_alert_at, created_at
                FROM incident
                ORDER BY last_alert_at DESC
                LIMIT 100
                """, (resultSet, rowNum) -> new IncidentSummary(UUID.fromString(resultSet.getString("id")),
                resultSet.getString("title"), resultSet.getString("primary_service"), resultSet.getString("alert_name"),
                resultSet.getString("severity"), resultSet.getString("status"), resultSet.getTimestamp("started_at").toInstant(),
                resultSet.getTimestamp("last_alert_at").toInstant(), resultSet.getTimestamp("created_at").toInstant()));
    }

    @GetMapping("/{incidentId}")
    public ResponseEntity<IncidentDetail> detail(@PathVariable UUID incidentId) {
        var details = jdbcTemplate.query("""
                SELECT i.id, i.title, i.primary_service, i.alert_name, i.severity, i.status, i.started_at, i.last_alert_at,
                       r.id AS run_id, r.status AS run_status, r.attempt, r.failure_code
                FROM incident i
                LEFT JOIN LATERAL (
                    SELECT id, status, attempt, failure_code
                    FROM diagnosis_run
                    WHERE incident_id = i.id
                    ORDER BY attempt DESC
                    LIMIT 1
                ) r ON TRUE
                WHERE i.id = ?
                """, (resultSet, rowNum) -> new IncidentDetail(UUID.fromString(resultSet.getString("id")),
                resultSet.getString("title"), resultSet.getString("primary_service"), resultSet.getString("alert_name"),
                resultSet.getString("severity"), resultSet.getString("status"), resultSet.getTimestamp("started_at").toInstant(),
                resultSet.getTimestamp("last_alert_at").toInstant(), nullableUuid(resultSet.getString("run_id")),
                resultSet.getString("run_status"), resultSet.getObject("attempt", Integer.class), resultSet.getString("failure_code")),
                incidentId);
        return details.isEmpty() ? ResponseEntity.notFound().build() : ResponseEntity.ok(details.get(0));
    }

    private static UUID nullableUuid(String value) {
        return value == null ? null : UUID.fromString(value);
    }

    public record IncidentSummary(UUID id, String title, String primaryService, String alertName, String severity, String status,
            Instant startedAt, Instant lastAlertAt, Instant createdAt) {
    }

    public record IncidentDetail(UUID id, String title, String primaryService, String alertName, String severity, String status,
            Instant startedAt, Instant lastAlertAt, UUID diagnosisRunId, String diagnosisRunStatus, Integer attempt,
            String failureCode) {
    }
}
