-- Prospective independent arms only. No old slots, budgets or scores are rewritten.
CREATE TABLE team_evolution_holdouts (
 contract_id BIGINT PRIMARY KEY,
 request_key VARCHAR(64) NOT NULL,
 fingerprint VARCHAR(64) NOT NULL,
 principal_digest VARCHAR(64) NOT NULL,
 frontier_digest VARCHAR(64) NOT NULL,
 holdout_digest VARCHAR(64) NOT NULL,
 baseline_evaluation_id VARCHAR(36) NOT NULL UNIQUE,
 selected_evaluation_id VARCHAR(36) NOT NULL UNIQUE,
 created_at TIMESTAMP(6) NOT NULL,
 CONSTRAINT fk_team_holdout_contract FOREIGN KEY(contract_id) REFERENCES team_evolution_contracts(id),
 CONSTRAINT fk_team_holdout_baseline FOREIGN KEY(baseline_evaluation_id) REFERENCES team_evolution_evaluations(id),
 CONSTRAINT fk_team_holdout_selected FOREIGN KEY(selected_evaluation_id) REFERENCES team_evolution_evaluations(id)
);
CREATE TABLE team_evolution_closures (
 contract_id BIGINT PRIMARY KEY,
 request_key VARCHAR(64) NOT NULL,
 fingerprint VARCHAR(64) NOT NULL,
 principal_digest VARCHAR(64) NOT NULL,
 result_json MEDIUMTEXT NOT NULL,
 created_at TIMESTAMP(6) NOT NULL,
 CONSTRAINT fk_team_closure_contract FOREIGN KEY(contract_id) REFERENCES team_evolution_contracts(id)
);
