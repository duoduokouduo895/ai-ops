ALTER TABLE incident
    ADD COLUMN correlation_key CHAR(64),
    ADD COLUMN environment VARCHAR(64),
    ADD COLUMN last_alert_at TIMESTAMPTZ;

UPDATE incident
SET correlation_key = 'legacy:' || id::text,
    environment = 'legacy',
    last_alert_at = COALESCE(detected_at, created_at)
WHERE correlation_key IS NULL;

ALTER TABLE incident
    ALTER COLUMN correlation_key SET NOT NULL,
    ALTER COLUMN environment SET NOT NULL,
    ALTER COLUMN last_alert_at SET NOT NULL;

CREATE UNIQUE INDEX uq_incident_active_correlation
    ON incident (correlation_key)
    WHERE status IN ('OPEN', 'DIAGNOSING', 'DIAGNOSED', 'FAILED');

CREATE INDEX idx_incident_correlation_history
    ON incident (correlation_key, created_at DESC);

ALTER TABLE diagnosis_run
    ADD COLUMN lease_token UUID,
    ADD COLUMN lease_generation BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN previous_run_id UUID REFERENCES diagnosis_run(id) ON DELETE SET NULL,
    ADD COLUMN failure_code VARCHAR(128);

ALTER TABLE diagnosis_run DROP CONSTRAINT ck_diagnosis_run_kind;
ALTER TABLE diagnosis_run
    ADD CONSTRAINT ck_diagnosis_run_kind
        CHECK (run_kind IN ('INITIAL', 'SUPPLEMENTAL', 'MANUAL_RETRY', 'AUTOMATIC_RETRY'));

ALTER TABLE diagnosis_run DROP CONSTRAINT ck_diagnosis_run_status;
ALTER TABLE diagnosis_run
    ADD CONSTRAINT ck_diagnosis_run_status
        CHECK (status IN (
            'PENDING', 'LEASED', 'COLLECTING', 'EVIDENCE_READY', 'ANALYZING', 'VERIFYING',
            'GENERATING_REPORT', 'RETRY_WAIT', 'COMPLETED', 'FAILED'
        ));

CREATE INDEX idx_diagnosis_run_lease_token
    ON diagnosis_run (id, lease_owner, lease_token)
    WHERE lease_token IS NOT NULL;
