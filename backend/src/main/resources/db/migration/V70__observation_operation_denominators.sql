-- Only new original submissions enroll. Historical operations and facts are never backfilled.
CREATE TABLE observation_operations (
 id BIGINT AUTO_INCREMENT PRIMARY KEY,
 entry_kind VARCHAR(32) NOT NULL,
 operation_key VARCHAR(128) NOT NULL UNIQUE,
 ax_task_id BIGINT,
 trial_id BIGINT,
 workflow_execution_id BIGINT,
 node_sequence INT,
 agent_id BIGINT,
 raw_trace_id VARCHAR(32),
 trace_id VARCHAR(32),
 runtime_id BIGINT,
 instance_key VARCHAR(128),
 endpoint VARCHAR(512),
 actor_uid VARCHAR(64),
 actor_template VARCHAR(128),
 registration_state VARCHAR(32) NOT NULL,
 expected_agent_spans INT,
 expected_model_events INT,
 span_name VARCHAR(64),
 created_at TIMESTAMP(6) NOT NULL,
 CONSTRAINT fk_observation_ax FOREIGN KEY(ax_task_id) REFERENCES ax_tasks(id) ON DELETE CASCADE,
 CONSTRAINT fk_observation_trial FOREIGN KEY(trial_id) REFERENCES agent_experiment_trials(id) ON DELETE CASCADE,
 CONSTRAINT fk_observation_workflow FOREIGN KEY(workflow_execution_id) REFERENCES workflow_executions(id) ON DELETE CASCADE
);
CREATE INDEX ix_observation_window ON observation_operations(created_at,entry_kind);
CREATE INDEX ix_observation_trace ON observation_operations(trace_id,agent_id,runtime_id);
CREATE TABLE observation_operation_events (
 operation_id BIGINT NOT NULL,
 event_kind VARCHAR(32) NOT NULL,
 reason_code VARCHAR(48) NOT NULL,
 owner_epoch BIGINT,
 created_at TIMESTAMP(6) NOT NULL,
 PRIMARY KEY(operation_id,event_kind),
 CONSTRAINT fk_observation_event FOREIGN KEY(operation_id) REFERENCES observation_operations(id) ON DELETE CASCADE
);
