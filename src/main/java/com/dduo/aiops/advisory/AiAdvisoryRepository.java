package com.dduo.aiops.advisory;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** Reads the immutable deterministic report and records every Stage 4 outcome separately. */
@Repository
public class AiAdvisoryRepository {

    private final JdbcTemplate jdbcTemplate;

    public AiAdvisoryRepository(JdbcTemplate jdbcTemplate) {
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

    /** Atomically reserves one outbound model call for the current UTC day. */
    public boolean reserveDailyRequest(int dailyLimit) {
        return Boolean.TRUE.equals(jdbcTemplate.queryForObject("""
                WITH reservation AS (
                    INSERT INTO ai_model_daily_budget (usage_date, request_count, updated_at)
                    VALUES ((CURRENT_TIMESTAMP AT TIME ZONE 'UTC')::date, 1, CURRENT_TIMESTAMP)
                    ON CONFLICT (usage_date) DO UPDATE
                    SET request_count = ai_model_daily_budget.request_count + 1,
                        updated_at = CURRENT_TIMESTAMP
                    WHERE ai_model_daily_budget.request_count < ?
                    RETURNING request_count
                )
                SELECT EXISTS (SELECT 1 FROM reservation)
                """, Boolean.class, dailyLimit));
    }

    @Transactional
    public int purgeExpiredData(int retentionDays) {
        var deletedAttempts = jdbcTemplate.update("""
                DELETE FROM ai_diagnosis_attempt
                WHERE created_at < CURRENT_TIMESTAMP - (? * INTERVAL '1 day')
                """, retentionDays);
        jdbcTemplate.update("""
                DELETE FROM ai_model_daily_budget
                WHERE usage_date < (CURRENT_TIMESTAMP AT TIME ZONE 'UTC')::date - ?
                """, retentionDays);
        return deletedAttempts;
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
