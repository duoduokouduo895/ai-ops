package com.dduo.aiops.diagnosis;

import java.math.BigDecimal;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** Persists a deterministic Stage 3 report only while the caller still owns its lease. */
@Repository
public class Stage3DiagnosisRepository {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public Stage3DiagnosisRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public Optional<ClaimedAnalysisRun> claimNext(String owner, int leaseSeconds) {
        var claimed = jdbcTemplate.query("""
                WITH candidate AS (
                    SELECT id
                    FROM diagnosis_run
                    WHERE (status = 'EVIDENCE_READY' AND available_at <= CURRENT_TIMESTAMP)
                       OR (status IN ('ANALYZING', 'VERIFYING', 'GENERATING_REPORT')
                           AND lease_until < CURRENT_TIMESTAMP)
                    ORDER BY available_at, created_at
                    FOR UPDATE SKIP LOCKED
                    LIMIT 1
                )
                UPDATE diagnosis_run run
                SET status = 'ANALYZING', lease_owner = ?, lease_token = gen_random_uuid(),
                    lease_generation = lease_generation + 1,
                    lease_until = CURRENT_TIMESTAMP + (? * INTERVAL '1 second'),
                    heartbeat_at = CURRENT_TIMESTAMP, started_at = COALESCE(started_at, CURRENT_TIMESTAMP),
                    updated_at = CURRENT_TIMESTAMP
                FROM candidate
                WHERE run.id = candidate.id
                RETURNING run.id, run.incident_id, run.attempt, run.lease_token
                """, (resultSet, rowNumber) -> new ClaimedAnalysisRun(UUID.fromString(resultSet.getString("id")),
                UUID.fromString(resultSet.getString("incident_id")), resultSet.getInt("attempt"), owner,
                UUID.fromString(resultSet.getString("lease_token"))), owner, leaseSeconds).stream().findFirst();
        claimed.ifPresent(run -> jdbcTemplate.update("""
                UPDATE incident SET status = 'DIAGNOSING', updated_at = CURRENT_TIMESTAMP
                WHERE id = ? AND status = 'OPEN'
                """, run.incidentId()));
        return claimed;
    }

    public IncidentContext incident(UUID incidentId) {
        return jdbcTemplate.queryForObject("""
                SELECT id, title, primary_service, alert_name, severity, started_at, last_alert_at
                FROM incident WHERE id = ?
                """, (resultSet, rowNumber) -> new IncidentContext(UUID.fromString(resultSet.getString("id")),
                resultSet.getString("title"), resultSet.getString("primary_service"), resultSet.getString("alert_name"),
                resultSet.getString("severity"), resultSet.getTimestamp("started_at").toInstant(),
                resultSet.getTimestamp("last_alert_at").toInstant()), incidentId);
    }

    @Transactional
    public Optional<ReportReceipt> finalizeReport(ClaimedAnalysisRun run, IncidentContext incident, DiagnosisResult result) {
        var movedToVerification = jdbcTemplate.update("""
                UPDATE diagnosis_run SET status = 'VERIFYING', updated_at = CURRENT_TIMESTAMP
                WHERE id = ? AND status = 'ANALYZING' AND lease_owner = ? AND lease_token = ?
                  AND lease_until > CURRENT_TIMESTAMP
                """, run.id(), run.owner(), run.leaseToken());
        if (movedToVerification != 1) {
            return Optional.empty();
        }

        var evidenceIds = new LinkedHashMap<EvidenceDraft, UUID>();
        for (var observation : result.observations()) {
            var auditId = jdbcTemplate.queryForObject("""
                    INSERT INTO tool_call_audit (diagnosis_run_id, tool_name, request, response_summary, status, duration_ms, error_message)
                    VALUES (?, ?, CAST(? AS jsonb), CAST(? AS jsonb), ?, ?, ?)
                    RETURNING id
                    """, (resultSet, rowNumber) -> UUID.fromString(resultSet.getString("id")), run.id(), observation.toolName(),
                    observation.requestJson(), observation.responseSummaryJson(), observation.status(), observation.durationMs(),
                    observation.errorMessage());
            if (observation.evidence() != null) {
                var evidence = observation.evidence();
                var evidenceId = jdbcTemplate.queryForObject("""
                        INSERT INTO evidence (incident_id, diagnosis_run_id, tool_call_id, evidence_type, source, service_name,
                                              start_time, end_time, query_spec, summary, raw_data, content_hash, importance)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb), ?, CAST(? AS jsonb), ?, ?)
                        RETURNING id
                        """, (resultSet, rowNumber) -> UUID.fromString(resultSet.getString("id")), incident.id(), run.id(), auditId,
                        evidence.type(), evidence.source(), incident.primaryService(), Timestamp.from(evidence.startTime()),
                        Timestamp.from(evidence.endTime()), evidence.querySpecJson(), evidence.summary(), evidence.rawDataJson(),
                        sha256(evidence.rawDataJson()), evidence.importance());
                evidenceIds.put(evidence, evidenceId);
            }
        }

        var candidateId = jdbcTemplate.queryForObject("""
                INSERT INTO root_cause_candidate (diagnosis_run_id, candidate_rank, name, description, affected_service,
                                                  support_score, verification_status, remediation)
                VALUES (?, 1, ?, ?, ?, ?, ?, CAST(? AS jsonb))
                RETURNING id
                """, (resultSet, rowNumber) -> UUID.fromString(resultSet.getString("id")), run.id(), result.candidateName(),
                result.description(), incident.primaryService(), result.supportScore(), result.verificationStatus(), result.remediationJson());
        for (var entry : evidenceIds.entrySet()) {
            jdbcTemplate.update("""
                    INSERT INTO candidate_evidence (candidate_id, evidence_id, relationship)
                    VALUES (?, ?, ?)
                    """, candidateId, entry.getValue(), entry.getKey().supportsCandidate() ? "SUPPORTING" : "OPPOSING");
        }

        // Keep an unverified candidate for auditability, but never present it as the
        // primary root cause. A report consumer must be able to distinguish an
        // evidence gap from a confirmed diagnosis without parsing Markdown.
        var primaryRootCauseId = result.isSupported() ? candidateId : null;
        var reportJson = reportJson(incident, run, result, candidateId, primaryRootCauseId, evidenceIds);
        var reportMarkdown = reportMarkdown(incident, result, evidenceIds);
        var reportId = jdbcTemplate.queryForObject("""
                INSERT INTO diagnosis_report (incident_id, diagnosis_run_id, report_version, primary_root_cause_id, report_json, report_markdown)
                VALUES (?, ?, 1, ?, CAST(? AS jsonb), ?)
                RETURNING id
                """, (resultSet, rowNumber) -> UUID.fromString(resultSet.getString("id")), incident.id(), run.id(), primaryRootCauseId,
                reportJson, reportMarkdown);

        var completed = jdbcTemplate.update("""
                UPDATE diagnosis_run
                SET status = 'COMPLETED', finished_at = CURRENT_TIMESTAMP, tool_call_count = ?,
                    lease_owner = NULL, lease_token = NULL, lease_until = NULL, updated_at = CURRENT_TIMESTAMP
                WHERE id = ? AND status = 'VERIFYING' AND lease_owner = ? AND lease_token = ?
                  AND lease_until > CURRENT_TIMESTAMP
                """, result.observations().size(), run.id(), run.owner(), run.leaseToken());
        if (completed != 1) {
            throw new IllegalStateException("Stage 3 lease expired before report completion");
        }
        jdbcTemplate.update("""
                UPDATE incident
                SET status = ?, updated_at = CURRENT_TIMESTAMP
                WHERE id = ? AND status IN ('OPEN', 'DIAGNOSING')
                """, result.isSupported() ? "DIAGNOSED" : "OPEN", incident.id());
        return Optional.of(new ReportReceipt(reportId, candidateId, evidenceIds.values().stream().toList()));
    }

    private String reportJson(IncidentContext incident, ClaimedAnalysisRun run, DiagnosisResult result, UUID candidateId,
            UUID primaryRootCauseId, Map<EvidenceDraft, UUID> evidenceIds) {
        var supportingEvidenceIds = evidenceIds.entrySet().stream().filter(entry -> entry.getKey().supportsCandidate())
                .map(entry -> entry.getValue().toString()).toList();
        var opposingEvidenceIds = evidenceIds.entrySet().stream().filter(entry -> !entry.getKey().supportsCandidate())
                .map(entry -> entry.getValue().toString()).toList();
        var report = new LinkedHashMap<String, Object>();
        report.put("incidentId", incident.id().toString());
        report.put("diagnosisRunId", run.id().toString());
        report.put("affectedServices", List.of(incident.primaryService()));
        report.put("candidateId", candidateId.toString());
        report.put("primaryRootCauseCandidateId", primaryRootCauseId == null ? null : primaryRootCauseId.toString());
        report.put("candidate", result.candidateName());
        report.put("evaluationGroundTruth", result.groundTruth());
        report.put("supportScore", result.supportScore());
        report.put("verificationStatus", result.verificationStatus());
        report.put("supportingEvidenceIds", supportingEvidenceIds);
        report.put("opposingEvidenceIds", opposingEvidenceIds);
        report.put("remediationTemplate", result.remediationJson());
        report.put("verificationSuggestion", result.isSupported()
                ? "恢复对应依赖或关闭隔离故障，再执行一次隐私安全的正常探测，确认告警和服务状态恢复。"
                : "补充遥测采集后重试；当前证据不足，不应执行基于该候选的处置。");
        report.put("riskWarning", "本报告只使用版本化固定规则，不执行自动修复，也不调用 LLM；Ground Truth 仅用于隔离故障实验评测。");
        report.put("limitations", result.limitations());
        report.put("automatedRemediation", false);
        return json(report);
    }

    private String reportMarkdown(IncidentContext incident, DiagnosisResult result, Map<EvidenceDraft, UUID> evidenceIds) {
        var supportingEvidence = evidenceLines(evidenceIds, true);
        var opposingEvidence = evidenceLines(evidenceIds, false);
        var conclusion = result.isSupported()
                ? result.candidateName() + "（support_score=" + result.supportScore() + "，SUPPORTED）"
                : "未确认主要根因；`" + result.candidateName() + "` 仅为未验证候选。";
        return "# AIDiag Stage 3 规则诊断报告\n\n" + "- Incident: `" + incident.id() + "`\n" + "- 影响服务: `"
                + incident.primaryService() + "`\n" + "- 告警: `" + incident.alertName() + "`\n\n" + "## 结论\n\n"
                + conclusion + "\n\n" + result.description() + "\n\n## 支持证据\n\n" + supportingEvidence
                + "\n## 反对或缺失证据\n\n" + opposingEvidence + "\n## 建议处理\n\n- `"
                + result.remediationJson() + "`\n\n## 验证建议\n\n- 恢复对应依赖或关闭隔离故障后，执行一次隐私安全的正常探测，确认服务恢复且告警 resolved。\n\n"
                + "## 风险提示\n\n- 本报告只使用版本化固定规则，不调用 LLM，不执行自动修复；Ground Truth 仅用于隔离故障实验评测。\n\n## 限制\n\n"
                + result.limitations().stream().map(item -> "- " + item).reduce("", (left, right) -> left + right + "\n");
    }

    private static String evidenceLines(Map<EvidenceDraft, UUID> evidenceIds, boolean supportsCandidate) {
        var lines = evidenceIds.entrySet().stream().filter(entry -> entry.getKey().supportsCandidate() == supportsCandidate)
                .map(entry -> "- `" + entry.getValue() + "`：" + entry.getKey().summary())
                .reduce("", (left, right) -> left + right + "\n");
        return lines.isBlank() ? "- 无\n" : lines;
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Cannot serialize diagnosis report", exception);
        }
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    public record ClaimedAnalysisRun(UUID id, UUID incidentId, int attempt, String owner, UUID leaseToken) {
    }

    public record IncidentContext(UUID id, String title, String primaryService, String alertName, String severity,
            Instant startedAt, Instant lastAlertAt) {
    }

    public record ToolObservation(String toolName, String requestJson, String responseSummaryJson, String status,
            Integer durationMs, String errorMessage, EvidenceDraft evidence) {
    }

    public record EvidenceDraft(String type, String source, Instant startTime, Instant endTime, String querySpecJson,
            String summary, String rawDataJson, BigDecimal importance, boolean supportsCandidate) {
    }

    public record DiagnosisResult(String candidateName, String groundTruth, String description, BigDecimal supportScore,
            String verificationStatus, String remediationJson, List<ToolObservation> observations, List<String> limitations) {
        public boolean isSupported() {
            return "SUPPORTED".equals(verificationStatus);
        }
    }

    public record ReportReceipt(UUID reportId, UUID candidateId, List<UUID> evidenceIds) {
    }
}
