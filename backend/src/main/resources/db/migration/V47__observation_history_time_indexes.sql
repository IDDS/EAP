CREATE INDEX idx_content_history_time ON content_observations(observed_at, id);
CREATE INDEX idx_tool_history_time ON tool_invocation_events(observed_at, id);
