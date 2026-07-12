-- Stage 4 proposals are deliberately isolated from diagnosis_report.  They
-- are advisory artefacts and can never replace the deterministic primary root
-- cause without a later, explicit review workflow.
ALTER TABLE diagnosis_report
    ADD CONSTRAINT uq_diagnosis_report_id_incident UNIQUE (id, incident_id);

CREATE TABLE ai_diagnosis_attempt (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    incident_id UUID NOT NULL REFERENCES incident(id) ON DELETE CASCADE,
    source_report_id UUID NOT NULL,
    status VARCHAR(32) NOT NULL,
    prompt_version VARCHAR(64) NOT NULL,
    model_provider VARCHAR(64),
    model_name VARCHAR(128),
    request_json JSONB NOT NULL,
    response_json JSONB,
    failure_code VARCHAR(128),
    failure_reason VARCHAR(1000),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at TIMESTAMPTZ,
    CONSTRAINT ck_ai_diagnosis_attempt_status
        CHECK (status IN ('PENDING', 'COMPLETED', 'FALLBACK', 'REJECTED', 'FAILED')),
    CONSTRAINT ck_ai_diagnosis_attempt_completion
        CHECK ((status = 'PENDING' AND completed_at IS NULL)
            OR (status <> 'PENDING' AND completed_at IS NOT NULL)),
    CONSTRAINT ck_ai_diagnosis_attempt_response
        CHECK ((status = 'PENDING' AND response_json IS NULL)
            OR (status <> 'PENDING' AND response_json IS NOT NULL)),
    CONSTRAINT ck_ai_diagnosis_attempt_failure
        CHECK ((status = 'COMPLETED' AND failure_code IS NULL)
            OR (status IN ('FALLBACK', 'REJECTED', 'FAILED') AND failure_code IS NOT NULL)
            OR status = 'PENDING'),
    CONSTRAINT fk_ai_diagnosis_attempt_source_report
        FOREIGN KEY (source_report_id, incident_id)
        REFERENCES diagnosis_report(id, incident_id) ON DELETE CASCADE
);

CREATE INDEX idx_ai_diagnosis_attempt_incident_created
    ON ai_diagnosis_attempt (incident_id, created_at DESC);
