ALTER TABLE alert_state_reconciliation
    DROP CONSTRAINT ck_alert_state_reconciliation_state;
ALTER TABLE alert_state_reconciliation
    ADD CONSTRAINT ck_alert_state_reconciliation_state
        CHECK (observed_state IN (
            'CONSISTENT',
            'ALERTMANAGER_ALERT_ABSENT',
            'ALERTMANAGER_ALERT_PRESENT',
            'SOURCE_UNAVAILABLE',
            'INCIDENT_RECONCILE_FAILED'
        ));
