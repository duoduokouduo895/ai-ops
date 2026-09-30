package com.dduo.aiops.console.api;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

/** Browser-safe projections. Raw payloads, query specs, prompts and model responses never cross this boundary. */
@Service
public class ConsoleIncidentQueryService {

    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public ConsoleIncidentQueryService(NamedParameterJdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    public IncidentPage incidents(String cursor, int limit, String service, String status, String severity,
            String search) {
        var where = new StringBuilder(" WHERE 1=1");
        var parameters = new MapSqlParameterSource("limit", limit + 1);
        if (service != null) {
            where.append(" AND i.primary_service = :service");
            parameters.addValue("service", service);
        }
        if (status != null) {
            where.append(" AND i.status = :status");
            parameters.addValue("status", status);
        }
        if (severity != null) {
            where.append(" AND i.severity = :severity");
            parameters.addValue("severity", severity);
        }
        if (search != null && !search.isBlank()) {
            where.append(" AND (i.title ILIKE :search OR i.alert_name ILIKE :search)");
            parameters.addValue("search", "%" + escapeLike(search.trim()) + "%");
        }
        var decoded = decodeCursor(cursor);
        if (decoded != null) {
            where.append(" AND (i.last_alert_at, i.id) < (:cursorTime, :cursorId)");
            parameters.addValue("cursorTime", Timestamp.from(decoded.lastAlertAt()));
            parameters.addValue("cursorId", decoded.id());
        }
        var rows = jdbcTemplate.query("""
                SELECT i.id, i.title, i.primary_service, i.alert_name, i.severity, i.status,
                       i.started_at, i.last_alert_at, i.resolved_at,
                       EXISTS (SELECT 1 FROM diagnosis_report r WHERE r.incident_id = i.id) AS has_report,
                       (SELECT count(*) FROM evidence e WHERE e.incident_id = i.id) AS evidence_count
                FROM incident i
                """ + where + " ORDER BY i.last_alert_at DESC, i.id DESC LIMIT :limit", parameters,
                (resultSet, rowNumber) -> new IncidentSummary(resultSet.getObject("id", UUID.class),
                        bounded(resultSet.getString("title"), 255), resultSet.getString("primary_service"),
                        resultSet.getString("alert_name"), resultSet.getString("severity"), resultSet.getString("status"),
                        instant(resultSet.getTimestamp("started_at")), instant(resultSet.getTimestamp("last_alert_at")),
                        instant(resultSet.getTimestamp("resolved_at")), resultSet.getBoolean("has_report"),
                        resultSet.getLong("evidence_count")));
        var hasMore = rows.size() > limit;
        var items = hasMore ? List.copyOf(rows.subList(0, limit)) : List.copyOf(rows);
        var nextCursor = hasMore && !items.isEmpty() ? encodeCursor(items.getLast().lastAlertAt(), items.getLast().id()) : null;
        return new IncidentPage(items, nextCursor, hasMore);
    }

    public IncidentDetail detail(UUID incidentId) {
        var parameters = new MapSqlParameterSource("incidentId", incidentId);
        return jdbcTemplate.query("""
                SELECT i.id, i.title, i.primary_service, i.alert_name, i.severity, i.status, i.environment,
                       i.started_at, i.detected_at, i.last_alert_at, i.resolved_at, i.updated_at,
                       r.id AS run_id, r.status AS run_status, r.attempt, r.failure_code,
                       r.started_at AS run_started_at, r.finished_at AS run_finished_at
                FROM incident i
                LEFT JOIN LATERAL (
                    SELECT id, status, attempt, failure_code, started_at, finished_at
                    FROM diagnosis_run WHERE incident_id = i.id ORDER BY attempt DESC LIMIT 1
                ) r ON TRUE
                WHERE i.id = :incidentId
                """, parameters, (resultSet, rowNumber) -> new IncidentDetail(
                        resultSet.getObject("id", UUID.class), bounded(resultSet.getString("title"), 255),
                        resultSet.getString("primary_service"), resultSet.getString("alert_name"),
                        resultSet.getString("severity"), resultSet.getString("status"), resultSet.getString("environment"),
                        instant(resultSet.getTimestamp("started_at")), instant(resultSet.getTimestamp("detected_at")),
                        instant(resultSet.getTimestamp("last_alert_at")), instant(resultSet.getTimestamp("resolved_at")),
                        instant(resultSet.getTimestamp("updated_at")), resultSet.getObject("run_id", UUID.class),
                        resultSet.getString("run_status"), resultSet.getObject("attempt", Integer.class),
                        resultSet.getString("failure_code"), instant(resultSet.getTimestamp("run_started_at")),
                        instant(resultSet.getTimestamp("run_finished_at"))))
                .stream().findFirst().orElseThrow(() -> new ConsoleResourceNotFoundException("incident"));
    }

    public ReportView report(UUID incidentId) {
        var parameters = new MapSqlParameterSource("incidentId", incidentId);
        var report = jdbcTemplate.query("""
                SELECT r.id, r.generated_at, r.diagnosis_run_id, r.primary_root_cause_id,
                       r.report_json -> 'limitations' AS limitations
                FROM diagnosis_report r
                WHERE r.incident_id = :incidentId
                ORDER BY r.generated_at DESC LIMIT 1
                """, parameters, (resultSet, rowNumber) -> new ReportProjection.ReportRecord(
                        resultSet.getObject("id", UUID.class), instant(resultSet.getTimestamp("generated_at")),
                        resultSet.getObject("diagnosis_run_id", UUID.class),
                        resultSet.getObject("primary_root_cause_id", UUID.class),
                        limitations(resultSet.getObject("limitations"))))
                .stream().findFirst().orElseThrow(() -> new ConsoleResourceNotFoundException("report"));
        var candidates = jdbcTemplate.query("""
                SELECT c.id, c.diagnosis_run_id, c.candidate_rank, c.name, c.description, c.affected_service,
                       c.support_score, c.verification_status,
                       (SELECT count(*) FROM candidate_evidence ce WHERE ce.candidate_id = c.id
                            AND ce.relationship = 'SUPPORTING') AS supporting_count,
                       (SELECT count(*) FROM candidate_evidence ce WHERE ce.candidate_id = c.id
                            AND ce.relationship = 'OPPOSING') AS opposing_count
                FROM root_cause_candidate c
                JOIN diagnosis_run run ON run.id = c.diagnosis_run_id
                WHERE run.incident_id = :incidentId AND c.diagnosis_run_id = :runId
                ORDER BY c.candidate_rank LIMIT 3
                """, parameters.addValue("runId", report.diagnosisRunId()),
                (resultSet, rowNumber) -> new ReportProjection.CandidateRecord(
                        resultSet.getObject("id", UUID.class), resultSet.getObject("diagnosis_run_id", UUID.class),
                        resultSet.getInt("candidate_rank"), resultSet.getString("name"),
                        resultSet.getString("description"), resultSet.getString("affected_service"),
                        resultSet.getObject("support_score", Double.class), resultSet.getString("verification_status"),
                        resultSet.getLong("supporting_count"), resultSet.getLong("opposing_count")));
        return ReportProjection.project(report, candidates);
    }

    public List<EvidenceView> evidence(UUID incidentId) {
        return jdbcTemplate.query("""
                SELECT id, diagnosis_run_id, evidence_type, source, service_name, start_time, end_time,
                       collected_at, summary, importance,
                       CASE WHEN evidence_type = 'METRIC' THEN raw_data END AS metric_raw
                FROM evidence WHERE incident_id = :incidentId
                ORDER BY collected_at, id LIMIT 500
                """, new MapSqlParameterSource("incidentId", incidentId),
                (resultSet, rowNumber) -> new EvidenceView(resultSet.getObject("id", UUID.class),
                        resultSet.getObject("diagnosis_run_id", UUID.class), resultSet.getString("evidence_type"),
                        resultSet.getString("source"), resultSet.getString("service_name"),
                        instant(resultSet.getTimestamp("start_time")), instant(resultSet.getTimestamp("end_time")),
                        instant(resultSet.getTimestamp("collected_at")), bounded(resultSet.getString("summary"), 2000),
                        resultSet.getDouble("importance"), metricSeries(resultSet.getObject("metric_raw"))));
    }

    /**
     * Daily incident counts in UTC days. The browser picks only the day count (7-30); buckets come from
     * generate_series so days without incidents are returned as explicit zeros rather than missing points.
     */
    public IncidentTrend trend(int days) {
        var today = LocalDate.now(ZoneOffset.UTC);
        var first = today.minusDays(days - 1L);
        var parameters = new MapSqlParameterSource("start", Timestamp.from(first.atStartOfDay(ZoneOffset.UTC).toInstant()))
                .addValue("end", Timestamp.from(today.atStartOfDay(ZoneOffset.UTC).toInstant()));
        var items = jdbcTemplate.query("""
                SELECT d.day,
                       count(i.id) FILTER (WHERE i.started_at >= d.day AND i.started_at < d.day + INTERVAL '1 day') AS opened,
                       count(i.id) FILTER (WHERE i.started_at >= d.day AND i.started_at < d.day + INTERVAL '1 day'
                                            AND i.severity = 'CRITICAL') AS critical,
                       count(i.id) FILTER (WHERE i.resolved_at >= d.day AND i.resolved_at < d.day + INTERVAL '1 day') AS resolved
                FROM generate_series(CAST(:start AS timestamptz), CAST(:end AS timestamptz), INTERVAL '1 day') AS d(day)
                LEFT JOIN incident i
                       ON (i.started_at >= d.day AND i.started_at < d.day + INTERVAL '1 day')
                       OR (i.resolved_at >= d.day AND i.resolved_at < d.day + INTERVAL '1 day')
                GROUP BY d.day ORDER BY d.day
                """, parameters, (rs, row) -> new TrendDay(
                        rs.getTimestamp("day").toInstant().atOffset(ZoneOffset.UTC).toLocalDate(),
                        rs.getLong("opened"), rs.getLong("critical"), rs.getLong("resolved")));
        return new IncidentTrend(days, "UTC", List.copyOf(items));
    }

    public List<TimelineEntry> timeline(UUID incidentId) {
        detail(incidentId);
        var parameters = new MapSqlParameterSource("incidentId", incidentId);
        var entries = new ArrayList<TimelineEntry>();
        entries.addAll(jdbcTemplate.query("""
                SELECT a.id, a.alert_status, a.starts_at, a.last_received_at
                FROM alert_event a JOIN incident_alert ia ON ia.alert_event_id = a.id
                WHERE ia.incident_id = :incidentId ORDER BY a.last_received_at LIMIT 200
                """, parameters, (rs, row) -> timeline(rs.getObject("id", UUID.class),
                        "ALERT_" + rs.getString("alert_status").toUpperCase(Locale.ROOT),
                        instant(rs.getTimestamp("last_received_at")), "Alertmanager state received", "ALERT_EVENT")));
        entries.addAll(jdbcTemplate.query("""
                SELECT id, status, COALESCE(finished_at, started_at, created_at) AS occurred_at, failure_code
                FROM diagnosis_run WHERE incident_id = :incidentId ORDER BY attempt LIMIT 50
                """, parameters, (rs, row) -> timeline(rs.getObject("id", UUID.class),
                        "DIAGNOSIS_" + rs.getString("status"), instant(rs.getTimestamp("occurred_at")),
                        rs.getString("failure_code") == null ? "Diagnosis run state"
                                : "Diagnosis run ended with a controlled failure",
                        "DIAGNOSIS_RUN")));
        entries.addAll(jdbcTemplate.query("""
                SELECT id, evidence_type, collected_at FROM evidence
                WHERE incident_id = :incidentId ORDER BY collected_at LIMIT 500
                """, parameters, (rs, row) -> timeline(rs.getObject("id", UUID.class),
                        "EVIDENCE_" + rs.getString("evidence_type"), instant(rs.getTimestamp("collected_at")),
                        "Redacted evidence collected", "EVIDENCE")));
        entries.addAll(jdbcTemplate.query("""
                SELECT id, generated_at FROM diagnosis_report
                WHERE incident_id = :incidentId ORDER BY generated_at LIMIT 50
                """, parameters, (rs, row) -> timeline(rs.getObject("id", UUID.class), "REPORT_GENERATED",
                        instant(rs.getTimestamp("generated_at")), "Deterministic report generated", "DIAGNOSIS_REPORT")));
        entries.sort(java.util.Comparator.comparing(TimelineEntry::occurredAt));
        return List.copyOf(entries);
    }

    public List<AiAttemptView> aiAttempts(UUID incidentId) {
        detail(incidentId);
        return jdbcTemplate.query("""
                SELECT id, source_report_id, status, prompt_version, model_provider, model_name,
                       failure_code, created_at, completed_at
                FROM ai_diagnosis_attempt WHERE incident_id = :incidentId
                ORDER BY created_at DESC LIMIT 100
                """, new MapSqlParameterSource("incidentId", incidentId),
                (rs, row) -> new AiAttemptView(rs.getObject("id", UUID.class),
                        rs.getObject("source_report_id", UUID.class), rs.getString("status"),
                        rs.getString("prompt_version"), rs.getString("model_provider"), rs.getString("model_name"),
                        rs.getString("failure_code"), instant(rs.getTimestamp("created_at")),
                        instant(rs.getTimestamp("completed_at"))));
    }

    public Overview overview() {
        return jdbcTemplate.queryForObject("""
                SELECT count(*) FILTER (WHERE status IN ('OPEN','DIAGNOSING','DIAGNOSED','FAILED')) AS active,
                       count(*) FILTER (WHERE status IN ('RESOLVED','CLOSED') AND resolved_at >= CURRENT_TIMESTAMP - INTERVAL '24 hours') AS recovered,
                       count(*) FILTER (WHERE status = 'FAILED') AS failed,
                       max(last_alert_at) AS last_alert_at,
                       (SELECT max(collected_at) FROM evidence) AS last_evidence_at,
                       (SELECT count(*) FROM diagnosis_run WHERE status IN ('PENDING','LEASED','COLLECTING','EVIDENCE_READY','ANALYZING','VERIFYING','GENERATING_REPORT','RETRY_WAIT')) AS pending_runs
                FROM incident
                """, new MapSqlParameterSource(), (rs, row) -> new Overview(rs.getLong("active"),
                        rs.getLong("recovered"), rs.getLong("failed"), rs.getLong("pending_runs"),
                        instant(rs.getTimestamp("last_alert_at")), instant(rs.getTimestamp("last_evidence_at"))));
    }

    private static Cursor decodeCursor(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return null;
        }
        try {
            var decoded = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.US_ASCII);
            var separator = decoded.indexOf('|');
            if (separator < 1) {
                throw new IllegalArgumentException("missing separator");
            }
            return new Cursor(Instant.parse(decoded.substring(0, separator)), UUID.fromString(decoded.substring(separator + 1)));
        } catch (RuntimeException exception) {
            throw new ConsoleInvalidCursorException();
        }
    }

    private static String encodeCursor(Instant instant, UUID id) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString((instant + "|" + id).getBytes(StandardCharsets.US_ASCII));
    }

    private static String escapeLike(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    private static Instant instant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }

    private static TimelineEntry timeline(UUID id, String type, Instant occurredAt, String summary,
            String referenceKind) {
        return new TimelineEntry(id, type, occurredAt, summary, referenceKind, id);
    }

    private List<String> limitations(Object raw) {
        if (raw == null) {
            return List.of();
        }
        try {
            JsonNode node = objectMapper.readTree(raw.toString());
            if (!node.isArray()) {
                return List.of();
            }
            var values = new ArrayList<String>();
            for (var item : node) {
                if (item.isTextual()) {
                    values.add(item.asText());
                }
            }
            return ReportProjection.boundedLimitations(values);
        } catch (Exception exception) {
            return List.of();
        }
    }

    /** Only numeric fields of METRIC raw data cross the boundary; query specs and labels never do. */
    MetricSeries metricSeries(Object raw) {
        if (raw == null) {
            return null;
        }
        try {
            JsonNode node = objectMapper.readTree(raw.toString());
            var threshold = finite(node.path("threshold"));
            var maximum = finite(node.path("maximum"));
            var points = new ArrayList<double[]>();
            for (var point : node.path("series")) {
                var time = finite(point.path(0));
                var value = finite(point.path(1));
                if (time != null && value != null && points.size() < MetricSeries.MAX_POINTS) {
                    points.add(new double[] {time, value});
                }
            }
            return new MetricSeries(threshold, maximum, List.copyOf(points));
        } catch (Exception exception) {
            return null;
        }
    }

    private static Double finite(JsonNode node) {
        return node.isNumber() && Double.isFinite(node.asDouble()) ? node.asDouble() : null;
    }

    private static String bounded(String value, int length) {
        if (value == null) {
            return null;
        }
        var cleaned = value.replaceAll("[\\p{Cntrl}&&[^\\r\\n\\t]]", " ").trim();
        return cleaned.length() <= length ? cleaned : cleaned.substring(0, length);
    }

    private record Cursor(Instant lastAlertAt, UUID id) { }
    public record IncidentPage(List<IncidentSummary> items, String nextCursor, boolean hasMore) { }
    public record IncidentSummary(UUID id, String title, String primaryService, String alertName, String severity,
            String status, Instant startedAt, Instant lastAlertAt, Instant resolvedAt, boolean hasReport,
            long evidenceCount) { }
    public record IncidentDetail(UUID id, String title, String primaryService, String alertName, String severity,
            String status, String environment, Instant startedAt, Instant detectedAt, Instant lastAlertAt,
            Instant resolvedAt, Instant updatedAt, UUID diagnosisRunId, String diagnosisRunStatus, Integer attempt,
            String failureCode, Instant diagnosisStartedAt, Instant diagnosisFinishedAt) { }
    public record ReportView(UUID id, Instant generatedAt, String rootCause, String description, String affectedService,
            Double supportScore, String verificationStatus, long supportingEvidence, long opposingEvidence,
            UUID diagnosisRunId, String disposition, String dispositionReason, List<String> limitations,
            List<ReportCandidateView> candidates) { }

    public record ReportCandidateView(UUID id, int rank, String name, String description, String affectedService,
            Double supportScore, String verificationStatus, long supportingEvidence, long opposingEvidence) { }
    public record EvidenceView(UUID id, UUID diagnosisRunId, String type, String source, String serviceName,
            Instant startTime, Instant endTime, Instant collectedAt, String summary, double importance,
            MetricSeries metric) { }
    /** Downsampled metric curve for METRIC evidence; points are {@code [epochSeconds, value]}. */
    public record MetricSeries(Double threshold, Double maximum, List<double[]> points) {
        static final int MAX_POINTS = 240;
    }
    public record IncidentTrend(int days, String zone, List<TrendDay> items) { }
    public record TrendDay(LocalDate day, long opened, long critical, long resolved) { }
    public record TimelineEntry(UUID evidenceId, String type, Instant occurredAt, String summary, String referenceKind,
            UUID referenceId) { }
    public record AiAttemptView(UUID id, UUID sourceReportId, String status, String promptVersion, String modelProvider,
            String modelName, String failureCode, Instant createdAt, Instant completedAt) { }
    public record Overview(long activeIncidents, long recoveredLast24Hours, long failedIncidents, long pendingRuns,
            Instant lastAlertAt, Instant lastEvidenceAt) { }
}
