CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE incident (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    title VARCHAR(255) NOT NULL,
    primary_service VARCHAR(128) NOT NULL,
    alert_name VARCHAR(128) NOT NULL,
    severity VARCHAR(32) NOT NULL,
    status VARCHAR(32) NOT NULL,
    started_at TIMESTAMPTZ NOT NULL,
    detected_at TIMESTAMPTZ NOT NULL,
    resolved_at TIMESTAMPTZ,
    closed_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT ck_incident_status
        CHECK (status IN ('OPEN', 'DIAGNOSING', 'DIAGNOSED', 'FAILED', 'RESOLVED', 'CLOSED')),
    CONSTRAINT ck_incident_severity
        CHECK (severity IN ('INFO', 'WARNING', 'CRITICAL')),
    CONSTRAINT ck_incident_time_order
        CHECK (detected_at + INTERVAL '5 minutes' >= started_at)
);

CREATE TABLE alert_event (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    receiver VARCHAR(128) NOT NULL,
    group_key VARCHAR(512) NOT NULL DEFAULT '',
    fingerprint VARCHAR(128) NOT NULL,
    alert_status VARCHAR(16) NOT NULL,
    starts_at TIMESTAMPTZ NOT NULL,
    ends_at TIMESTAMPTZ,
    labels JSONB NOT NULL,
    annotations JSONB NOT NULL DEFAULT '{}'::jsonb,
    raw_payload JSONB NOT NULL,
    payload_hash CHAR(64) NOT NULL,
    receive_count INTEGER NOT NULL DEFAULT 1,
    first_received_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_received_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_alert_event_idempotency
        UNIQUE (receiver, group_key, fingerprint, alert_status, starts_at),
    CONSTRAINT ck_alert_event_status
        CHECK (alert_status IN ('firing', 'resolved')),
    CONSTRAINT ck_alert_event_receive_count
        CHECK (receive_count > 0),
    CONSTRAINT ck_alert_event_time_order
        CHECK (ends_at IS NULL OR ends_at >= starts_at)
);

CREATE TABLE incident_alert (
    incident_id UUID NOT NULL REFERENCES incident(id) ON DELETE CASCADE,
    alert_event_id UUID NOT NULL REFERENCES alert_event(id) ON DELETE RESTRICT,
    linked_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (incident_id, alert_event_id)
);

CREATE TABLE incident_affected_service (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    incident_id UUID NOT NULL REFERENCES incident(id) ON DELETE CASCADE,
    service_name VARCHAR(128) NOT NULL,
    impact_role VARCHAR(32) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_incident_affected_service UNIQUE (incident_id, service_name),
    CONSTRAINT ck_incident_affected_service_role
        CHECK (impact_role IN ('ROOT', 'SYMPTOM', 'DOWNSTREAM', 'UNKNOWN'))
);

CREATE TABLE diagnosis_run (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    incident_id UUID NOT NULL REFERENCES incident(id) ON DELETE CASCADE,
    attempt INTEGER NOT NULL,
    run_kind VARCHAR(32) NOT NULL,
    status VARCHAR(32) NOT NULL,
    available_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    lease_owner VARCHAR(128),
    lease_until TIMESTAMPTZ,
    heartbeat_at TIMESTAMPTZ,
    started_at TIMESTAMPTZ,
    finished_at TIMESTAMPTZ,
    model_provider VARCHAR(64),
    model_name VARCHAR(128),
    model_version VARCHAR(128),
    prompt_version VARCHAR(64),
    tool_schema_version VARCHAR(64),
    tool_call_count INTEGER NOT NULL DEFAULT 0,
    token_usage JSONB NOT NULL DEFAULT '{}'::jsonb,
    failure_reason VARCHAR(2000),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uq_diagnosis_run_attempt UNIQUE (incident_id, attempt),
    CONSTRAINT ck_diagnosis_run_attempt CHECK (attempt > 0),
    CONSTRAINT ck_diagnosis_run_kind
        CHECK (run_kind IN ('INITIAL', 'SUPPLEMENTAL', 'MANUAL_RETRY')),
    CONSTRAINT ck_diagnosis_run_status
        CHECK (status IN (
            'PENDING', 'LEASED', 'COLLECTING', 'ANALYZING', 'VERIFYING',
            'GENERATING_REPORT', 'RETRY_WAIT', 'COMPLETED', 'FAILED'
        )),
    CONSTRAINT ck_diagnosis_run_tool_count CHECK (tool_call_count >= 0),
    CONSTRAINT ck_diagnosis_run_time_order
        CHECK (finished_at IS NULL OR started_at IS NULL OR finished_at >= started_at)
);

CREATE INDEX idx_diagnosis_run_claim
    ON diagnosis_run (status, available_at, lease_until)
    WHERE status IN ('PENDING', 'RETRY_WAIT', 'LEASED');
CREATE INDEX idx_diagnosis_run_incident
    ON diagnosis_run (incident_id, attempt DESC);

CREATE TABLE tool_call_audit (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    diagnosis_run_id UUID NOT NULL REFERENCES diagnosis_run(id) ON DELETE CASCADE,
    tool_name VARCHAR(128) NOT NULL,
    request JSONB NOT NULL,
    response_summary JSONB,
    status VARCHAR(16) NOT NULL,
    duration_ms BIGINT,
    error_message VARCHAR(2000),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_tool_call_status
        CHECK (status IN ('STARTED', 'SUCCEEDED', 'FAILED', 'TIMED_OUT', 'REJECTED')),
    CONSTRAINT ck_tool_call_duration
        CHECK (duration_ms IS NULL OR duration_ms >= 0)
);

CREATE INDEX idx_tool_call_audit_run
    ON tool_call_audit (diagnosis_run_id, created_at);

CREATE TABLE evidence (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    incident_id UUID NOT NULL REFERENCES incident(id) ON DELETE CASCADE,
    diagnosis_run_id UUID NOT NULL REFERENCES diagnosis_run(id) ON DELETE CASCADE,
    tool_call_id UUID REFERENCES tool_call_audit(id) ON DELETE RESTRICT,
    evidence_type VARCHAR(32) NOT NULL,
    source VARCHAR(128) NOT NULL,
    service_name VARCHAR(128) NOT NULL,
    start_time TIMESTAMPTZ NOT NULL,
    end_time TIMESTAMPTZ NOT NULL,
    collected_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    query_spec JSONB NOT NULL,
    summary TEXT NOT NULL,
    raw_data JSONB NOT NULL,
    content_hash CHAR(64) NOT NULL,
    importance NUMERIC(5,2) NOT NULL,
    expires_at TIMESTAMPTZ,
    CONSTRAINT ck_evidence_type
        CHECK (evidence_type IN ('METRIC', 'LOG', 'TRACE', 'TOPOLOGY', 'RUNBOOK', 'CHANGE_HISTORY')),
    CONSTRAINT ck_evidence_time_order CHECK (end_time >= start_time),
    CONSTRAINT ck_evidence_importance CHECK (importance BETWEEN 0 AND 100),
    CONSTRAINT uq_evidence_snapshot
        UNIQUE (diagnosis_run_id, evidence_type, source, service_name, content_hash)
);

CREATE INDEX idx_evidence_incident_type
    ON evidence (incident_id, evidence_type, collected_at);
CREATE INDEX idx_evidence_run
    ON evidence (diagnosis_run_id, collected_at);

CREATE TABLE root_cause_candidate (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    diagnosis_run_id UUID NOT NULL REFERENCES diagnosis_run(id) ON DELETE CASCADE,
    candidate_rank SMALLINT NOT NULL,
    name VARCHAR(255) NOT NULL,
    description TEXT NOT NULL,
    affected_service VARCHAR(128) NOT NULL,
    support_score NUMERIC(5,2) NOT NULL,
    verification_status VARCHAR(32) NOT NULL,
    remediation JSONB NOT NULL DEFAULT '[]'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_root_cause_candidate_rank UNIQUE (diagnosis_run_id, candidate_rank),
    CONSTRAINT ck_root_cause_candidate_rank CHECK (candidate_rank BETWEEN 1 AND 3),
    CONSTRAINT ck_root_cause_support_score CHECK (support_score BETWEEN 0 AND 100),
    CONSTRAINT ck_root_cause_verification_status
        CHECK (verification_status IN (
            'UNVERIFIED', 'SUPPORTED', 'WEAKLY_SUPPORTED', 'REJECTED', 'INSUFFICIENT_EVIDENCE'
        ))
);

CREATE TABLE candidate_evidence (
    candidate_id UUID NOT NULL REFERENCES root_cause_candidate(id) ON DELETE CASCADE,
    evidence_id UUID NOT NULL REFERENCES evidence(id) ON DELETE RESTRICT,
    relationship VARCHAR(16) NOT NULL,
    rationale VARCHAR(1000),
    PRIMARY KEY (candidate_id, evidence_id, relationship),
    CONSTRAINT ck_candidate_evidence_relationship
        CHECK (relationship IN ('SUPPORTING', 'OPPOSING'))
);

CREATE TABLE diagnosis_report (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    incident_id UUID NOT NULL REFERENCES incident(id) ON DELETE CASCADE,
    diagnosis_run_id UUID NOT NULL REFERENCES diagnosis_run(id) ON DELETE CASCADE,
    report_version INTEGER NOT NULL,
    primary_root_cause_id UUID REFERENCES root_cause_candidate(id) ON DELETE SET NULL,
    report_json JSONB NOT NULL,
    report_markdown TEXT NOT NULL,
    generated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_diagnosis_report_version UNIQUE (diagnosis_run_id, report_version),
    CONSTRAINT ck_diagnosis_report_version CHECK (report_version > 0)
);

CREATE TABLE runbook_document (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    title VARCHAR(255) NOT NULL,
    service_name VARCHAR(128),
    component VARCHAR(128) NOT NULL,
    fault_type VARCHAR(128) NOT NULL,
    applicable_version VARCHAR(128),
    environment VARCHAR(64),
    severity VARCHAR(32),
    content_hash CHAR(64) NOT NULL UNIQUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE runbook_chunk (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    document_id UUID NOT NULL REFERENCES runbook_document(id) ON DELETE CASCADE,
    chunk_index INTEGER NOT NULL,
    content TEXT NOT NULL,
    content_hash CHAR(64) NOT NULL,
    embedding vector,
    embedding_model VARCHAR(128),
    embedding_dimensions INTEGER,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_runbook_chunk_index UNIQUE (document_id, chunk_index),
    CONSTRAINT uq_runbook_chunk_hash UNIQUE (document_id, content_hash),
    CONSTRAINT ck_runbook_chunk_index CHECK (chunk_index >= 0),
    CONSTRAINT ck_runbook_embedding_dimensions
        CHECK (embedding_dimensions IS NULL OR embedding_dimensions > 0)
);

CREATE INDEX idx_runbook_document_metadata
    ON runbook_document (service_name, component, fault_type, environment);

CREATE OR REPLACE FUNCTION set_updated_at()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = CURRENT_TIMESTAMP;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_incident_updated_at
    BEFORE UPDATE ON incident
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_diagnosis_run_updated_at
    BEFORE UPDATE ON diagnosis_run
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_runbook_document_updated_at
    BEFORE UPDATE ON runbook_document
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();
