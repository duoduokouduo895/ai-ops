ALTER TABLE candidate_evidence
    DROP CONSTRAINT candidate_evidence_evidence_id_fkey;

ALTER TABLE candidate_evidence
    ADD CONSTRAINT candidate_evidence_evidence_id_fkey
    FOREIGN KEY (evidence_id) REFERENCES evidence(id) ON DELETE CASCADE;
