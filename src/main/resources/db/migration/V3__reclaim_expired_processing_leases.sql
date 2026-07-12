CREATE INDEX idx_diagnosis_run_expired_processing_lease
    ON diagnosis_run (lease_until)
    WHERE status IN ('LEASED', 'COLLECTING', 'ANALYZING', 'VERIFYING', 'GENERATING_REPORT');
