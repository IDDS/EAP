-- Explicit prospective plans only; never enroll historical contracts or allocate defaults.
CREATE TABLE team_evolution_budget_plans (
 contract_id BIGINT PRIMARY KEY,
 plan_key VARCHAR(64) NOT NULL UNIQUE,
 request_key VARCHAR(64) NOT NULL,
 fingerprint VARCHAR(64) NOT NULL,
 principal_digest VARCHAR(64) NOT NULL,
 business_task_json MEDIUMTEXT NOT NULL,
 business_task_digest VARCHAR(64) NOT NULL,
 max_calls BIGINT NOT NULL,
 max_output_tokens BIGINT NOT NULL,
 required_calls BIGINT NOT NULL,
 approval_expires_at TIMESTAMP(6) NOT NULL,
 live_bindings_json TEXT NOT NULL,
 created_at TIMESTAMP(6) NOT NULL,
 CONSTRAINT fk_team_budget_contract FOREIGN KEY(contract_id) REFERENCES team_evolution_contracts(id)
);
