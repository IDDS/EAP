ALTER TABLE agent_plan_steps ADD COLUMN execution_backend VARCHAR(16) NOT NULL DEFAULT 'RUNTIME';
ALTER TABLE ax_tasks ADD COLUMN workflow_id BIGINT NULL;
ALTER TABLE ax_tasks ADD COLUMN workflow_run_key VARCHAR(64) NULL;
ALTER TABLE ax_tasks ADD COLUMN workflow_node_key VARCHAR(64) NULL;
CREATE INDEX idx_ax_workflow_run ON ax_tasks(workflow_run_key);
