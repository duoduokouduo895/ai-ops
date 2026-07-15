package com.dduo.aiops.console.security;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** Stores only hashes of browser credentials and bounded security audit metadata. */
@Repository
public class ConsoleSessionRepository {

    private final JdbcTemplate jdbcTemplate;

    public ConsoleSessionRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Optional<SessionRecord> findActive(String tokenHash, Instant now) {
        return jdbcTemplate.query("""
                SELECT id, principal, created_at, last_seen_at, idle_expires_at, absolute_expires_at
                FROM console_session
                WHERE token_hash = ? AND revoked_at IS NULL
                  AND idle_expires_at > ? AND absolute_expires_at > ?
                """, (resultSet, rowNumber) -> new SessionRecord(resultSet.getObject("id", UUID.class),
                resultSet.getString("principal"), resultSet.getTimestamp("created_at").toInstant(),
                resultSet.getTimestamp("last_seen_at").toInstant(), resultSet.getTimestamp("idle_expires_at").toInstant(),
                resultSet.getTimestamp("absolute_expires_at").toInstant()), tokenHash, timestamp(now), timestamp(now))
                .stream().findFirst();
    }

    @Transactional
    public UUID create(String principal, String tokenHash, Instant now, Instant idleExpiresAt, Instant absoluteExpiresAt,
            int maximumSessions) {
        jdbcTemplate.queryForObject("SELECT pg_advisory_xact_lock(hashtext(?))", (resultSet, rowNumber) -> 1,
                "console-session:" + principal);
        var id = jdbcTemplate.queryForObject("""
                INSERT INTO console_session
                    (principal, token_hash, created_at, last_seen_at, idle_expires_at, absolute_expires_at)
                VALUES (?, ?, ?, ?, ?, ?)
                RETURNING id
                """, (resultSet, rowNumber) -> resultSet.getObject(1, UUID.class), principal, tokenHash, timestamp(now),
                timestamp(now), timestamp(idleExpiresAt), timestamp(absoluteExpiresAt));
        jdbcTemplate.update("""
                WITH excess AS (
                    SELECT id FROM console_session
                    WHERE principal = ? AND revoked_at IS NULL
                    ORDER BY created_at DESC, id DESC
                    OFFSET ?
                )
                UPDATE console_session session
                SET revoked_at = CURRENT_TIMESTAMP, revoke_reason = 'SESSION_LIMIT'
                FROM excess WHERE session.id = excess.id
                """, principal, maximumSessions);
        return id;
    }

    public void touch(UUID sessionId, Instant now, Instant idleExpiresAt) {
        jdbcTemplate.update("""
                UPDATE console_session
                SET last_seen_at = ?, idle_expires_at = LEAST(absolute_expires_at, ?)
                WHERE id = ? AND revoked_at IS NULL AND last_seen_at < ?
                """, timestamp(now), timestamp(idleExpiresAt), sessionId, timestamp(now.minusSeconds(60)));
    }

    public void rotateCsrf(UUID sessionId, String csrfHash) {
        var updated = jdbcTemplate.update("""
                UPDATE console_session SET csrf_hash = ?
                WHERE id = ? AND revoked_at IS NULL
                  AND idle_expires_at > CURRENT_TIMESTAMP AND absolute_expires_at > CURRENT_TIMESTAMP
                """, csrfHash, sessionId);
        if (updated != 1) {
            throw new ConsoleSessionExpiredException();
        }
    }

    public boolean csrfMatches(UUID sessionId, String csrfHash) {
        return Boolean.TRUE.equals(jdbcTemplate.queryForObject("""
                SELECT EXISTS (
                    SELECT 1 FROM console_session
                    WHERE id = ? AND csrf_hash = ? AND revoked_at IS NULL
                      AND idle_expires_at > CURRENT_TIMESTAMP AND absolute_expires_at > CURRENT_TIMESTAMP
                )
                """, Boolean.class, sessionId, csrfHash));
    }

    public void revoke(UUID sessionId, String reason) {
        jdbcTemplate.update("""
                UPDATE console_session
                SET revoked_at = COALESCE(revoked_at, CURRENT_TIMESTAMP),
                    revoke_reason = COALESCE(revoke_reason, ?)
                WHERE id = ?
                """, reason, sessionId);
    }

    public int recentFailures(String principalHash, String sourceHash, Instant since) {
        var count = jdbcTemplate.queryForObject("""
                SELECT count(*) FROM console_login_attempt
                WHERE principal_hash = ? AND source_hash = ? AND outcome = 'FAILED' AND attempted_at >= ?
                """, Integer.class, principalHash, sourceHash, timestamp(since));
        return count == null ? 0 : count;
    }

    public void lockLoginKey(String principalHash, String sourceHash) {
        jdbcTemplate.queryForObject("SELECT pg_advisory_xact_lock(hashtext(?))", (resultSet, rowNumber) -> 1,
                "console-login:" + principalHash + ":" + sourceHash);
    }

    public void recordLoginAttempt(String principalHash, String sourceHash, String outcome) {
        jdbcTemplate.update("""
                INSERT INTO console_login_attempt (principal_hash, source_hash, outcome)
                VALUES (?, ?, ?)
                """, principalHash, sourceHash, outcome);
    }

    public void audit(String principal, UUID sessionId, String eventType, String outcome, String requestId) {
        jdbcTemplate.update("""
                INSERT INTO console_security_audit (principal, session_id, event_type, outcome, request_id)
                VALUES (?, ?, ?, ?, ?)
                """, principal, sessionId, eventType, outcome, bounded(requestId, 128));
    }

    @Transactional
    public int purge(int retentionDays) {
        var deletedAudit = jdbcTemplate.update("""
                DELETE FROM console_security_audit
                WHERE occurred_at < CURRENT_TIMESTAMP - (? * INTERVAL '1 day')
                """, retentionDays);
        jdbcTemplate.update("""
                DELETE FROM console_login_attempt
                WHERE attempted_at < CURRENT_TIMESTAMP - (? * INTERVAL '1 day')
                """, retentionDays);
        jdbcTemplate.update("""
                DELETE FROM console_session
                WHERE COALESCE(revoked_at, absolute_expires_at) < CURRENT_TIMESTAMP - (? * INTERVAL '1 day')
                """, retentionDays);
        return deletedAudit;
    }

    private static Timestamp timestamp(Instant value) {
        return Timestamp.from(value);
    }

    private static String bounded(String value, int maximumLength) {
        if (value == null) {
            return null;
        }
        var cleaned = value.replaceAll("\\p{Cntrl}", " ").trim();
        return cleaned.length() <= maximumLength ? cleaned : cleaned.substring(0, maximumLength);
    }

    public record SessionRecord(UUID id, String principal, Instant createdAt, Instant lastSeenAt,
            Instant idleExpiresAt, Instant absoluteExpiresAt) {
    }
}
