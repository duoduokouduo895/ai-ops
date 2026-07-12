-- A primary root cause is a presentation-level assertion.  It must always
-- refer to a candidate from the same diagnosis run that has independent
-- evidence support; application code alone is not a sufficient boundary.
UPDATE diagnosis_report report
SET primary_root_cause_id = NULL,
    report_json = jsonb_set(report.report_json, '{primaryRootCauseCandidateId}', 'null'::jsonb, true),
    report_markdown = report.report_markdown
        || E'\n\n## 历史数据修正\n\n- 该报告原主要根因未满足 `SUPPORTED` 约束，迁移时已撤销主要根因标记；候选与证据仍保留供审计。\n'
WHERE primary_root_cause_id IS NOT NULL
  AND NOT EXISTS (
      SELECT 1
      FROM root_cause_candidate candidate
      WHERE candidate.id = report.primary_root_cause_id
        AND candidate.diagnosis_run_id = report.diagnosis_run_id
        AND candidate.verification_status = 'SUPPORTED'
  );

CREATE OR REPLACE FUNCTION validate_primary_root_cause()
RETURNS TRIGGER AS $$
BEGIN
    IF NEW.primary_root_cause_id IS NOT NULL
       AND NOT EXISTS (
           SELECT 1
           FROM root_cause_candidate candidate
           WHERE candidate.id = NEW.primary_root_cause_id
             AND candidate.diagnosis_run_id = NEW.diagnosis_run_id
             AND candidate.verification_status = 'SUPPORTED'
       ) THEN
        RAISE EXCEPTION 'primary_root_cause_id must reference a SUPPORTED candidate from the diagnosis run'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_diagnosis_report_primary_root_cause
    BEFORE INSERT OR UPDATE OF primary_root_cause_id, diagnosis_run_id ON diagnosis_report
    FOR EACH ROW EXECUTE FUNCTION validate_primary_root_cause();

CREATE OR REPLACE FUNCTION protect_referenced_primary_root_cause()
RETURNS TRIGGER AS $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM diagnosis_report report
        WHERE report.primary_root_cause_id = NEW.id
          AND (NEW.verification_status <> 'SUPPORTED'
               OR report.diagnosis_run_id <> NEW.diagnosis_run_id)
    ) THEN
        RAISE EXCEPTION 'a referenced primary root cause must remain SUPPORTED and in the report diagnosis run'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_root_cause_candidate_protect_primary
    BEFORE UPDATE OF verification_status, diagnosis_run_id ON root_cause_candidate
    FOR EACH ROW EXECUTE FUNCTION protect_referenced_primary_root_cause();
