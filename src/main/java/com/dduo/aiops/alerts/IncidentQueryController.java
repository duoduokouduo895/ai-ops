package com.dduo.aiops.alerts;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Bounded read-only Incident API for the Stage 5.4 ADMIN BFF. */
@Validated
@RestController
@RequestMapping("/api/v1/incidents")
public class IncidentQueryController {

    private static final String SERVICE_PATTERN = "[A-Za-z0-9._-]{1,128}";

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public IncidentQueryController(NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @GetMapping
    public IncidentPage list(@RequestParam(defaultValue = "0") @Min(0) @Max(10_000) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            @RequestParam(required = false) @Pattern(regexp = SERVICE_PATTERN) String service,
            @RequestParam(required = false) IncidentStatus status,
            @RequestParam(required = false) IncidentSeverity severity) {
        var where = new StringBuilder(" WHERE 1=1");
        var parameters = new MapSqlParameterSource().addValue("limit", size).addValue("offset", Math.multiplyExact(page, size));
        if (service != null) {
            where.append(" AND primary_service = :service");
            parameters.addValue("service", service);
        }
        if (status != null) {
            where.append(" AND status = :status");
            parameters.addValue("status", status.name());
        }
        if (severity != null) {
            where.append(" AND severity = :severity");
            parameters.addValue("severity", severity.name());
        }

        var total = jdbcTemplate.queryForObject("SELECT count(*) FROM incident" + where, parameters, Long.class);
        var items = jdbcTemplate.query("""
                SELECT id, title, primary_service, alert_name, severity, status, started_at, last_alert_at, created_at,
                       EXISTS (SELECT 1 FROM diagnosis_report report WHERE report.incident_id = incident.id) AS has_report
                FROM incident
                incident """ + where + " ORDER BY last_alert_at DESC, id DESC LIMIT :limit OFFSET :offset",
                parameters, (resultSet, rowNum) -> new IncidentSummary(UUID.fromString(resultSet.getString("id")),
                        resultSet.getString("title"), resultSet.getString("primary_service"),
                        resultSet.getString("alert_name"), resultSet.getString("severity"), resultSet.getString("status"),
                        resultSet.getTimestamp("started_at").toInstant(), resultSet.getTimestamp("last_alert_at").toInstant(),
                        resultSet.getTimestamp("created_at").toInstant(), resultSet.getBoolean("has_report")));
        return new IncidentPage(items, page, size, total == null ? 0 : total);
    }

    @GetMapping("/shadow-summary")
    public ShadowSummary shadowSummary(
            @RequestParam(defaultValue = "ai-chat-candidate") @Pattern(regexp = SERVICE_PATTERN) String service,
            @RequestParam(defaultValue = "24") @Min(1) @Max(720) int windowHours) {
        var parameters = new MapSqlParameterSource().addValue("service", service).addValue("windowHours", windowHours);
        return jdbcTemplate.queryForObject("""
                WITH filtered AS (
                    SELECT * FROM incident
                    WHERE primary_service = :service
                      AND started_at >= CURRENT_TIMESTAMP - make_interval(hours => :windowHours)
                ), latest_report AS (
                    SELECT DISTINCT ON (report.incident_id)
                           report.incident_id, report.diagnosis_run_id, report.primary_root_cause_id, report.generated_at
                    FROM diagnosis_report report
                    JOIN filtered incident ON incident.id = report.incident_id
                    ORDER BY report.incident_id, report.generated_at DESC
                )
                SELECT count(*) AS total_incidents,
                       count(*) FILTER (WHERE incident.status IN ('OPEN','DIAGNOSING','DIAGNOSED','FAILED')) AS active_incidents,
                       count(*) FILTER (WHERE incident.status IN ('RESOLVED','CLOSED')) AS resolved_incidents,
                       count(report.incident_id) AS report_count,
                       count(*) FILTER (WHERE candidate.verification_status = 'SUPPORTED') AS supported_reports,
                       count(*) FILTER (WHERE report.incident_id IS NOT NULL
                           AND candidate.verification_status IS DISTINCT FROM 'SUPPORTED') AS abstained_reports,
                       count(*) FILTER (WHERE report.incident_id IS NOT NULL
                           AND EXISTS (SELECT 1 FROM evidence metric WHERE metric.diagnosis_run_id = report.diagnosis_run_id
                                       AND metric.evidence_type = 'METRIC')
                           AND EXISTS (SELECT 1 FROM evidence log WHERE log.diagnosis_run_id = report.diagnosis_run_id
                                       AND log.evidence_type = 'LOG')) AS evidence_complete_reports,
                       COALESCE(round(avg(EXTRACT(EPOCH FROM (report.generated_at - incident.started_at)) * 1000))::bigint, 0)
                           AS average_report_latency_ms
                FROM filtered incident
                LEFT JOIN latest_report report ON report.incident_id = incident.id
                LEFT JOIN root_cause_candidate candidate ON candidate.id = report.primary_root_cause_id
                """, parameters, (resultSet, rowNum) -> new ShadowSummary(service, windowHours,
                        resultSet.getLong("total_incidents"), resultSet.getLong("active_incidents"),
                        resultSet.getLong("resolved_incidents"), resultSet.getLong("report_count"),
                        resultSet.getLong("supported_reports"), resultSet.getLong("abstained_reports"),
                        resultSet.getLong("evidence_complete_reports"), resultSet.getLong("average_report_latency_ms")));
    }

    @GetMapping("/{incidentId}")
    public ResponseEntity<IncidentDetail> detail(@PathVariable UUID incidentId) {
        var parameters = new MapSqlParameterSource("incidentId", incidentId);
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
                WHERE i.id = :incidentId
                """, parameters, (resultSet, rowNum) -> new IncidentDetail(UUID.fromString(resultSet.getString("id")),
                resultSet.getString("title"), resultSet.getString("primary_service"), resultSet.getString("alert_name"),
                resultSet.getString("severity"), resultSet.getString("status"), resultSet.getTimestamp("started_at").toInstant(),
                resultSet.getTimestamp("last_alert_at").toInstant(), nullableUuid(resultSet.getString("run_id")),
                resultSet.getString("run_status"), resultSet.getObject("attempt", Integer.class), resultSet.getString("failure_code")));
        return details.isEmpty() ? ResponseEntity.notFound().build() : ResponseEntity.ok(details.get(0));
    }

    private static UUID nullableUuid(String value) {
        return value == null ? null : UUID.fromString(value);
    }

    public enum IncidentStatus { OPEN, DIAGNOSING, DIAGNOSED, FAILED, RESOLVED, CLOSED }

    public enum IncidentSeverity { INFO, WARNING, CRITICAL }

    public record IncidentPage(List<IncidentSummary> items, int page, int size, long total) { }

    public record IncidentSummary(UUID id, String title, String primaryService, String alertName, String severity, String status,
            Instant startedAt, Instant lastAlertAt, Instant createdAt, boolean hasReport) { }

    public record IncidentDetail(UUID id, String title, String primaryService, String alertName, String severity, String status,
            Instant startedAt, Instant lastAlertAt, UUID diagnosisRunId, String diagnosisRunStatus, Integer attempt,
            String failureCode) { }

    public record ShadowSummary(String service, int windowHours, long totalIncidents, long activeIncidents,
            long resolvedIncidents, long reportCount, long supportedReports, long abstainedReports,
            long evidenceCompleteReports, long averageReportLatencyMs) { }
}
