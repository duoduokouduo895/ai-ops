CREATE TABLE console_session (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    principal VARCHAR(128) NOT NULL,
    token_hash CHAR(64) NOT NULL UNIQUE,
    csrf_hash CHAR(64),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_seen_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    idle_expires_at TIMESTAMPTZ NOT NULL,
    absolute_expires_at TIMESTAMPTZ NOT NULL,
    revoked_at TIMESTAMPTZ,
    revoke_reason VARCHAR(64),
    CONSTRAINT ck_console_session_expiry
        CHECK (idle_expires_at >= created_at AND absolute_expires_at >= idle_expires_at),
    CONSTRAINT ck_console_session_revoke_reason
        CHECK ((revoked_at IS NULL AND revoke_reason IS NULL)
            OR (revoked_at IS NOT NULL AND revoke_reason IS NOT NULL))
);

CREATE INDEX idx_console_session_active_principal
    ON console_session (principal, created_at DESC)
    WHERE revoked_at IS NULL;

CREATE INDEX idx_console_session_expiry
    ON console_session (idle_expires_at, absolute_expires_at)
    WHERE revoked_at IS NULL;

CREATE TABLE console_login_attempt (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    principal_hash CHAR(64) NOT NULL,
    source_hash CHAR(64) NOT NULL,
    outcome VARCHAR(16) NOT NULL,
    attempted_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_console_login_attempt_outcome
        CHECK (outcome IN ('SUCCEEDED', 'FAILED', 'LOCKED'))
);

CREATE INDEX idx_console_login_attempt_lockout
    ON console_login_attempt (principal_hash, source_hash, attempted_at DESC);

CREATE TABLE console_security_audit (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    principal VARCHAR(128),
    session_id UUID REFERENCES console_session(id) ON DELETE SET NULL,
    event_type VARCHAR(64) NOT NULL,
    outcome VARCHAR(16) NOT NULL,
    request_id VARCHAR(128),
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_console_security_audit_outcome
        CHECK (outcome IN ('SUCCEEDED', 'REJECTED', 'EXPIRED', 'REVOKED'))
);

CREATE INDEX idx_console_security_audit_time
    ON console_security_audit (occurred_at DESC);
