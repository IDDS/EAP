CREATE TABLE runtime_tool_policies (
    agent_id BIGINT PRIMARY KEY,
    revision BIGINT NOT NULL,
    enabled BOOLEAN NOT NULL,
    allowed_tools_json JSON NOT NULL,
    claim_limit BIGINT NOT NULL,
    claimed_count BIGINT NOT NULL DEFAULT 0,
    updated_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT fk_runtime_tool_policy_agent FOREIGN KEY (agent_id) REFERENCES agents(id),
    CONSTRAINT chk_runtime_tool_policy_budget CHECK (claimed_count >= 0 AND claim_limit >= claimed_count)
);
ALTER TABLE runtime_tool_calls ADD COLUMN policy_revision BIGINT NULL;
