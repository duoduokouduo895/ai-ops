-- A small, database-enforced budget prevents concurrent application instances
-- from exceeding the configured number of outbound model calls per UTC day.
CREATE TABLE ai_model_daily_budget (
    usage_date DATE PRIMARY KEY,
    request_count INTEGER NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_ai_model_daily_budget_count CHECK (request_count >= 0)
);
