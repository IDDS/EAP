-- Legacy executions retain version zero and are never enrolled for automatic recovery.
ALTER TABLE workflow_executions ADD COLUMN recovery_version INT NOT NULL DEFAULT 0;
ALTER TABLE workflow_executions ADD COLUMN owner_epoch BIGINT NOT NULL DEFAULT 0;
ALTER TABLE workflow_executions ADD COLUMN owner_id VARCHAR(36);
CREATE TABLE workflow_tool_recovery (
 id BIGINT AUTO_INCREMENT PRIMARY KEY, execution_id BIGINT NOT NULL, sequence_number INT NOT NULL,
 node_key VARCHAR(128) NOT NULL, task_key VARCHAR(192) NOT NULL,
 root_id VARCHAR(36) NOT NULL, operation_id VARCHAR(36) NOT NULL,
 binding_json TEXT NOT NULL, request_json TEXT NOT NULL,
 state VARCHAR(24) NOT NULL, reason VARCHAR(256) NOT NULL,
 owner_epoch BIGINT NOT NULL DEFAULT 0, owner_id VARCHAR(36), lease_until_ms BIGINT,
 next_check_ms BIGINT NOT NULL, observed_ms BIGINT, evidence_json TEXT,
 created_ms BIGINT NOT NULL,
 UNIQUE(execution_id,sequence_number), UNIQUE(root_id,operation_id),
 FOREIGN KEY(execution_id) REFERENCES workflow_executions(id)
);
CREATE INDEX idx_workflow_tool_query_due ON workflow_tool_recovery(state,next_check_ms,lease_until_ms);
