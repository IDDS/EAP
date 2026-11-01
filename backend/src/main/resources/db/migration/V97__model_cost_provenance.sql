-- Null preserves uncertainty for old records and records written by older versions.
ALTER TABLE model_invocation_events ADD COLUMN cost_provenance VARCHAR(16) NULL;
