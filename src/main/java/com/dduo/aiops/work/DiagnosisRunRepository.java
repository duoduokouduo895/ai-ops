package com.dduo.aiops.work;

import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** Database-owned queue semantics. Every mutation is fenced by the active lease token. */
@Repository
public class DiagnosisRunRepository {

    private final JdbcTemplate jdbcTemplate;

    public DiagnosisRunRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Transactional
    public Optional<ClaimedRun> claimNext(String owner, int leaseSeconds) {
        var claimed = jdbcTemplate.query("""
                WITH candidate AS (
                    SELECT id
                    FROM diagnosis_run
                    WHERE (status IN ('PENDING', 'RETRY_WAIT') AND available_at <= CURRENT_TIMESTAMP)
                       OR (status IN ('LEASED', 'COLLECTING')
                           AND lease_until < CURRENT_TIMESTAMP)
                    ORDER BY available_at, created_at
                    FOR UPDATE SKIP LOCKED
                    LIMIT 1
                )
                UPDATE diagnosis_run run
                SET status = 'LEASED',
                    lease_owner = ?,
                    lease_token = gen_random_uuid(),
                    lease_generation = lease_generation + 1,
                    lease_until = CURRENT_TIMESTAMP + (? * INTERVAL '1 second'),
                    heartbeat_at = CURRENT_TIMESTAMP,
                    started_at = COALESCE(started_at, CURRENT_TIMESTAMP),
                    updated_at = CURRENT_TIMESTAMP
                FROM candidate
                WHERE run.id = candidate.id
                RETURNING run.id, run.incident_id, run.attempt, run.lease_token
                """, (resultSet, rowNum) -> new ClaimedRun(UUID.fromString(resultSet.getString("id")),
                UUID.fromString(resultSet.getString("incident_id")), resultSet.getInt("attempt"), owner,
                UUID.fromString(resultSet.getString("lease_token"))), owner, leaseSeconds);
        return claimed.stream().findFirst();
    }

    public boolean moveToCollecting(ClaimedRun run) {
        return jdbcTemplate.update("""
                UPDATE diagnosis_run
                SET status = 'COLLECTING', updated_at = CURRENT_TIMESTAMP
                WHERE id = ? AND status = 'LEASED' AND lease_owner = ? AND lease_token = ?
                  AND lease_until > CURRENT_TIMESTAMP
                """, run.id(), run.owner(), run.leaseToken()) == 1;
    }

    public boolean heartbeat(ClaimedRun run, int leaseSeconds) {
        return jdbcTemplate.update("""
                UPDATE diagnosis_run
                SET heartbeat_at = CURRENT_TIMESTAMP,
                    lease_until = CURRENT_TIMESTAMP + (? * INTERVAL '1 second'),
                    updated_at = CURRENT_TIMESTAMP
                WHERE id = ? AND status IN ('COLLECTING', 'ANALYZING', 'VERIFYING', 'GENERATING_REPORT')
                  AND lease_owner = ? AND lease_token = ?
                  AND lease_until > CURRENT_TIMESTAMP
                """, leaseSeconds, run.id(), run.owner(), run.leaseToken()) == 1;
    }

    @Transactional
    public boolean recordStageTwoHandoff(ClaimedRun run) {
        return recordStageTwoHandoff(run, 0);
    }

    @Transactional
    public boolean recordStageTwoHandoff(ClaimedRun run, int stageThreeDelaySeconds) {
        var auditId = jdbcTemplate.query("""
                WITH handed_off AS (
                    UPDATE diagnosis_run
                    SET status = 'EVIDENCE_READY', finished_at = CURRENT_TIMESTAMP,
                        lease_owner = NULL, lease_token = NULL, lease_until = NULL,
                        available_at = CURRENT_TIMESTAMP + (? * INTERVAL '1 second'), updated_at = CURRENT_TIMESTAMP
                    WHERE id = ? AND status = 'COLLECTING' AND lease_owner = ? AND lease_token = ?
                      AND lease_until > CURRENT_TIMESTAMP
                    RETURNING id
                )
                INSERT INTO tool_call_audit (diagnosis_run_id, tool_name, request, response_summary, status, duration_ms)
                SELECT id, 'stage2-ingestion-handoff', '{"stage":"2"}'::jsonb,
                       '{"reportStatus":"PENDING"}'::jsonb, 'SUCCEEDED', 0
                FROM handed_off
                RETURNING id
                """, (resultSet, rowNum) -> UUID.fromString(resultSet.getString("id")), stageThreeDelaySeconds, run.id(), run.owner(),
                run.leaseToken());
        return !auditId.isEmpty();
    }

    @Transactional
    public boolean scheduleRetry(ClaimedRun run, int maxAttempts, int backoffSeconds, String failureCode, String failureReason) {
        var failed = jdbcTemplate.update("""
                UPDATE diagnosis_run
                SET status = 'FAILED', failure_code = ?, failure_reason = ?, finished_at = CURRENT_TIMESTAMP,
                    lease_owner = NULL, lease_token = NULL, lease_until = NULL, updated_at = CURRENT_TIMESTAMP
                WHERE id = ? AND status IN ('LEASED', 'COLLECTING', 'ANALYZING', 'VERIFYING', 'GENERATING_REPORT')
                  AND lease_owner = ? AND lease_token = ? AND lease_until > CURRENT_TIMESTAMP
                """, failureCode, truncate(failureReason), run.id(), run.owner(), run.leaseToken());
        if (failed != 1) {
            return false;
        }
        if (run.attempt() >= maxAttempts) {
            jdbcTemplate.update("""
                    UPDATE incident
                    SET status = 'FAILED', updated_at = CURRENT_TIMESTAMP
                    WHERE id = ? AND status IN ('OPEN', 'DIAGNOSING')
                    """, run.incidentId());
            return true;
        }
        jdbcTemplate.update("""
                INSERT INTO diagnosis_run (incident_id, attempt, run_kind, status, available_at, previous_run_id)
                VALUES (?, ?, 'AUTOMATIC_RETRY', 'RETRY_WAIT', CURRENT_TIMESTAMP + (? * INTERVAL '1 second'), ?)
                """, run.incidentId(), run.attempt() + 1, backoffSeconds, run.id());
        return true;
    }

    private static String truncate(String value) {
        return value == null ? "worker failure" : value.substring(0, Math.min(value.length(), 2000));
    }

    public record ClaimedRun(UUID id, UUID incidentId, int attempt, String owner, UUID leaseToken) {
    }
}
