CREATE TABLE eap_evaluation_scan_state (
    id BIGINT NOT NULL PRIMARY KEY,
    tool_cursor BIGINT NOT NULL DEFAULT 0,
    tool_upper BIGINT NOT NULL DEFAULT 0,
    span_cursor BIGINT NOT NULL DEFAULT 0,
    span_upper BIGINT NOT NULL DEFAULT 0,
    rag_cursor BIGINT NOT NULL DEFAULT 0,
    rag_upper BIGINT NOT NULL DEFAULT 0,
    lease_owner VARCHAR(64),
    lease_until TIMESTAMP(6) NULL,
    updated_at TIMESTAMP(6) NULL
);
INSERT INTO eap_evaluation_scan_state (id) VALUES (1);
ALTER TABLE eap_evaluation_jobs ADD COLUMN phase VARCHAR(32) NOT NULL DEFAULT 'QUEUED';
ALTER TABLE eap_evaluation_jobs ADD COLUMN processed INTEGER NOT NULL DEFAULT 0;
ALTER TABLE eap_evaluation_jobs ADD COLUMN total INTEGER NOT NULL DEFAULT 0;
CREATE INDEX idx_trace_scan_identity ON trace_span_observations (trace_id, id);
