CREATE INDEX idx_tasks_history_time ON agent_tasks(created_at, id);
CREATE INDEX idx_tasks_history_status ON agent_tasks(status, created_at, id);
CREATE INDEX idx_audit_history_time ON audit_logs(created_at, id);
CREATE INDEX idx_audit_history_result ON audit_logs(result, created_at, id);
