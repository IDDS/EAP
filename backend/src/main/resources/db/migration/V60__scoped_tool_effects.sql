CREATE TABLE scoped_tool_lock (id BIGINT PRIMARY KEY);
INSERT INTO scoped_tool_lock(id) VALUES(1);
CREATE TABLE scoped_tool_grants (
 grant_id VARCHAR(36) PRIMARY KEY, root_id VARCHAR(36) NOT NULL, parent_id VARCHAR(36),
 holder_key VARCHAR(128) NOT NULL, agent_id BIGINT NOT NULL, actor_uid VARCHAR(128) NOT NULL,
 generation VARCHAR(36) NOT NULL, policy_revision BIGINT NOT NULL, contract_version VARCHAR(32) NOT NULL,
 resources_json TEXT NOT NULL, recipients_json TEXT NOT NULL, bindings_json TEXT NOT NULL,
 max_delta BIGINT NOT NULL, claim_limit BIGINT NOT NULL, used_count BIGINT NOT NULL DEFAULT 0,
 depth INT NOT NULL, fingerprint VARCHAR(64) NOT NULL, state VARCHAR(16) NOT NULL,
 expires_at TIMESTAMP(6) NOT NULL, created_at TIMESTAMP(6) NOT NULL, created_by VARCHAR(128) NOT NULL,
 FOREIGN KEY(parent_id) REFERENCES scoped_tool_grants(grant_id)
);
CREATE INDEX idx_scoped_grant_holder ON scoped_tool_grants(holder_key,created_at);
CREATE INDEX idx_scoped_grant_root ON scoped_tool_grants(root_id);
CREATE TABLE scoped_tool_counters (
 resource_id VARCHAR(64) PRIMARY KEY, root_id VARCHAR(36) NOT NULL,
 counter_value BIGINT NOT NULL DEFAULT 0, effect_count BIGINT NOT NULL DEFAULT 0,
 FOREIGN KEY(root_id) REFERENCES scoped_tool_grants(grant_id)
);
CREATE TABLE scoped_tool_operations (
 root_id VARCHAR(36) NOT NULL, operation_id VARCHAR(36) NOT NULL, grant_id VARCHAR(36) NOT NULL,
 holder_key VARCHAR(128) NOT NULL, generation VARCHAR(36) NOT NULL, agent_id BIGINT NOT NULL,
 resource_id VARCHAR(64) NOT NULL, delta_value BIGINT NOT NULL, trace_id VARCHAR(32) NOT NULL,
 fingerprint VARCHAR(64) NOT NULL, state VARCHAR(24) NOT NULL, reason_code VARCHAR(64) NOT NULL,
 effect_id VARCHAR(36), counter_value BIGINT, effect_source VARCHAR(32) NOT NULL,
 created_at TIMESTAMP(6) NOT NULL, completed_at TIMESTAMP(6),
 PRIMARY KEY(root_id,operation_id), UNIQUE(effect_id),
 FOREIGN KEY(root_id) REFERENCES scoped_tool_grants(grant_id),
 FOREIGN KEY(grant_id) REFERENCES scoped_tool_grants(grant_id)
);
CREATE INDEX idx_scoped_operation_holder ON scoped_tool_operations(holder_key,created_at);
ALTER TABLE runtime_tool_calls ADD COLUMN evidence_source VARCHAR(32) NOT NULL DEFAULT 'RUNTIME_REPORTED';
ALTER TABLE runtime_tool_calls ADD COLUMN effect_id VARCHAR(36);
ALTER TABLE runtime_tool_calls ADD COLUMN root_grant_id VARCHAR(36);
