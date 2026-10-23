CREATE TABLE agentops_signal_sync_state (
    id BIGINT NOT NULL PRIMARY KEY,
    last_completed_at TIMESTAMP(6) NULL,
    last_created_count INT NOT NULL DEFAULT 0
);
INSERT INTO agentops_signal_sync_state (id, last_completed_at, last_created_count) VALUES (1, NULL, 0);

CREATE INDEX idx_agentops_tasks_window ON agent_tasks(updated_at, agent_id);
CREATE INDEX idx_agentops_content_window ON content_observations(observed_at, agent_id);
CREATE INDEX idx_agentops_tools_window ON tool_invocation_events(observed_at, agent_id);
