package com.dduo.aiops.aid;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Reads the immutable deterministic report and records every Stage 4 outcome separately. */
@Repository
public class Stage4Repository {

    private final JdbcTemplate jdbcTemplate;

    public Stage4Repository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Optional<ReportSnapshot> latestReport(UUID incidentId) {
        return jdbcTemplate.query("""
                SELECT report.id, report.diagnosis_run_id, incident.primary_service, incident.alert_name, incident.severity,
                       report.report_json::text
                FROM diagnosis_report report
                JOIN incident ON incident.id = report.incident_id
                WHERE report.incident_id = ?
                ORDER BY report.generated_at DESC
                LIMIT 1
                """, (resultSet, rowNumber) -> new ReportSnapshot(UUID.fromString(resultSet.getString(1)),
                UUID.fromString(resultSet.getString(2)), resultSet.getString(3), resultSet.getString(4), resultSet.getString(5),
                resultSet.getString(6)), incidentId).stream().findFirst();
    }

    public List<EvidenceSnapshot> evidence(UUID diagnosisRunId, int maxEvidence) {
        return jdbcTemplate.query("""
                SELECT id, evidence_type, source, summary
                FROM evidence
                WHERE diagnosis_run_id = ?
                ORDER BY importance DESC, collected_at ASC
                LIMIT ?
                """, (resultSet, rowNumber) -> new EvidenceSnapshot(resultSet.getObject(1, UUID.class), resultSet.getString(2),
                resultSet.getString(3), resultSet.getString(4)), diagnosisRunId, maxEvidence);
    }

    public UUID startAttempt(UUID incidentId, UUID sourceReportId, String promptVersion, String requestJson,
            String provider, String modelName) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO ai_diagnosis_attempt (incident_id, source_report_id, status, prompt_version, model_provider, model_name,
                                                  request_json)
                VALUES (?, ?, 'PENDING', ?, ?, ?, CAST(? AS jsonb))
                RETURNING id
                """, (resultSet, rowNumber) -> resultSet.getObject(1, UUID.class), incidentId, sourceReportId, promptVersion,
                provider, modelName, requestJson);
    }

    public void completeAttempt(UUID attemptId, String status, String responseJson, String failureCode, String failureReason) {
        var updated = jdbcTemplate.update("""
                UPDATE ai_diagnosis_attempt
                SET status = ?, response_json = CAST(? AS jsonb), failure_code = ?, failure_reason = ?, completed_at = CURRENT_TIMESTAMP
                WHERE id = ? AND status = 'PENDING'
                """, status, responseJson, failureCode, failureReason, attemptId);
        if (updated != 1) {
            throw new IllegalStateException("Stage 4 attempt is no longer pending: " + attemptId);
        }
    }

    public record ReportSnapshot(UUID reportId, UUID diagnosisRunId, String serviceName, String alertName, String severity,
            String reportJson) {
    }

    public record EvidenceSnapshot(UUID id, String type, String source, String summary) {
    }
}
