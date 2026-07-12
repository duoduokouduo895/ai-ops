ALTER TABLE incident
    ALTER COLUMN title TYPE VARCHAR(512);

CREATE UNIQUE INDEX uq_tool_call_audit_stage2_handoff
    ON tool_call_audit (diagnosis_run_id)
    WHERE tool_name = 'stage2-ingestion-handoff';
