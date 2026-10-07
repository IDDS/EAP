-- No historical root or operation is enrolled. New bindings are immutable and spend no budget.
CREATE TABLE workflow_tool_effect_roots (
 root_id VARCHAR(36) PRIMARY KEY,
 execution_id BIGINT NOT NULL,
 dispatch_epoch BIGINT NOT NULL,
 dispatch_owner VARCHAR(36) NOT NULL,
 created_ms BIGINT NOT NULL,
 FOREIGN KEY(execution_id) REFERENCES workflow_executions(id),
 FOREIGN KEY(root_id) REFERENCES scoped_tool_grants(grant_id)
);
