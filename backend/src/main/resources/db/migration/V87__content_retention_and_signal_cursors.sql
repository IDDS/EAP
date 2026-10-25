ALTER TABLE business_content_records ADD COLUMN raw_erased_at TIMESTAMP(6) NULL;
CREATE INDEX idx_content_retention_expiry ON business_content_records (raw_retained, expires_at, id);
CREATE INDEX idx_content_retention_received ON business_content_records (created_at, id);

ALTER TABLE agentops_signal_sync_state ADD COLUMN approval_cursor BIGINT NOT NULL DEFAULT 0;
ALTER TABLE agentops_signal_sync_state ADD COLUMN approval_upper_bound BIGINT NOT NULL DEFAULT 0;
ALTER TABLE agentops_signal_sync_state ADD COLUMN evaluation_cursor BIGINT NOT NULL DEFAULT 0;
ALTER TABLE agentops_signal_sync_state ADD COLUMN evaluation_upper_bound BIGINT NOT NULL DEFAULT 0;
CREATE INDEX idx_governance_signal_scan ON governance_preflight_decisions (approval_status, id);
CREATE INDEX idx_evaluation_signal_scan ON eap_evaluation_results (status, passed, id);
