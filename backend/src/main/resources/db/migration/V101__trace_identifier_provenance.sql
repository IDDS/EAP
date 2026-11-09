ALTER TABLE trace_span_observations ADD COLUMN trace_id_source VARCHAR(24) NOT NULL DEFAULT 'UNKNOWN';
ALTER TABLE trace_span_observations ADD COLUMN span_id_source VARCHAR(24) NOT NULL DEFAULT 'UNKNOWN';
