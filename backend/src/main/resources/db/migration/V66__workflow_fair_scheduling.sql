-- Historical executions are not enrolled or modified.
CREATE TABLE workflow_schedule_lock (id INT PRIMARY KEY, dispatch_sequence BIGINT NOT NULL);
INSERT INTO workflow_schedule_lock VALUES (1, 0);
CREATE TABLE workflow_schedule_groups (
 group_key VARCHAR(64) PRIMARY KEY,
 last_dispatch_sequence BIGINT NOT NULL
);
CREATE TABLE workflow_schedule_entries (
 execution_id BIGINT PRIMARY KEY,
 group_key VARCHAR(64) NOT NULL,
 queued_ms BIGINT NOT NULL,
 dispatch_ms BIGINT,
 dispatch_sequence BIGINT,
 selection_reason VARCHAR(32),
 FOREIGN KEY(execution_id) REFERENCES workflow_executions(id),
 FOREIGN KEY(group_key) REFERENCES workflow_schedule_groups(group_key)
);
CREATE INDEX ix_workflow_schedule_queue ON workflow_schedule_entries(group_key, queued_ms, execution_id);
CREATE TABLE workflow_phase_observations (
 execution_id BIGINT NOT NULL,
 owner_epoch BIGINT NOT NULL,
 owner_id VARCHAR(36) NOT NULL,
 node_sequence INT NOT NULL,
 phase_kind VARCHAR(32) NOT NULL,
 state VARCHAR(32) NOT NULL,
 started_ms BIGINT NOT NULL,
 ended_ms BIGINT,
 PRIMARY KEY(execution_id, owner_epoch, node_sequence, phase_kind),
 FOREIGN KEY(execution_id) REFERENCES workflow_executions(id)
);
