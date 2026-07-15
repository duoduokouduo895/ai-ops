CREATE TABLE alert_state_reconciliation (
    incident_id UUID PRIMARY KEY REFERENCES incident(id) ON DELETE CASCADE,
    observed_state VARCHAR(32) NOT NULL,
    consecutive_observations INTEGER NOT NULL DEFAULT 0,
    first_observed_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_observed_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_error_code VARCHAR(128),
    CONSTRAINT ck_alert_state_reconciliation_state
        CHECK (observed_state IN ('CONSISTENT', 'ALERTMANAGER_ALERT_ABSENT', 'ALERTMANAGER_ALERT_PRESENT', 'SOURCE_UNAVAILABLE')),
    CONSTRAINT ck_alert_state_reconciliation_count CHECK (consecutive_observations >= 0)
);
