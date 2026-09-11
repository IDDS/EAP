-- Do not backfill from observed_at: it can be a receive-time fallback.
ALTER TABLE trace_span_observations ADD COLUMN started_at TIMESTAMP(6) NULL;
ALTER TABLE trace_span_observations ADD COLUMN ended_at TIMESTAMP(6) NULL;
