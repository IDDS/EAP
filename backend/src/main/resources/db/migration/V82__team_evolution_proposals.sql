-- Existing approvals remain offline. Model proposal consent is prospective and immutable.
ALTER TABLE team_evolution_budget_plans ADD COLUMN proposal_mode VARCHAR(32) NOT NULL DEFAULT 'OFFLINE_ONLY';
ALTER TABLE team_evolution_budget_plans ADD COLUMN max_proposal_calls INT NOT NULL DEFAULT 0;
ALTER TABLE team_evolution_budget_plans ADD COLUMN max_proposal_tokens INT NOT NULL DEFAULT 0;
CREATE TABLE team_evolution_proposals (
 contract_id BIGINT NOT NULL,
 request_key VARCHAR(64) NOT NULL,
 fingerprint VARCHAR(64) NOT NULL,
 parent_id VARCHAR(64) NOT NULL,
 role_name VARCHAR(64) NOT NULL,
 agent_id BIGINT NOT NULL,
 trace_id VARCHAR(32) NOT NULL UNIQUE,
 model_request_digest VARCHAR(64) NOT NULL,
 request_json MEDIUMTEXT NOT NULL,
 state VARCHAR(32) NOT NULL,
 reason_code VARCHAR(64),
 configuration_json MEDIUMTEXT,
 created_at TIMESTAMP(6) NOT NULL,
 finished_at TIMESTAMP(6),
 PRIMARY KEY(contract_id,request_key),
 CONSTRAINT fk_team_proposal_parent FOREIGN KEY(contract_id,parent_id) REFERENCES team_evolution_candidates(contract_id,candidate_id)
);
