ALTER TABLE workflow_executions ADD COLUMN stop_version INT NOT NULL DEFAULT 0;

CREATE TABLE workflow_stop_requests (
    execution_id BIGINT NOT NULL PRIMARY KEY,
    request_key VARCHAR(64) NOT NULL,
    requester_hash VARCHAR(64) NOT NULL,
    requested_by VARCHAR(128) NOT NULL,
    requested_ms BIGINT NOT NULL,
    applied_ms BIGINT NULL,
    CONSTRAINT fk_workflow_stop_execution FOREIGN KEY (execution_id) REFERENCES workflow_executions(id)
);
