ALTER TABLE raw_events
    ADD COLUMN analysis_attempts SMALLINT NOT NULL DEFAULT 0;
