-- Prospective timing and platform-derived evidence only. Never backfill historical receipts.
CREATE TABLE team_evolution_sample_responsibilities (
 evaluation_id VARCHAR(36) NOT NULL,
 sample_id VARCHAR(64) NOT NULL,
 holder VARCHAR(36) NOT NULL,
 started_at TIMESTAMP(6) NOT NULL,
 PRIMARY KEY(evaluation_id,sample_id),
 CONSTRAINT fk_team_sample_execution FOREIGN KEY(evaluation_id) REFERENCES team_evolution_executions(evaluation_id)
);
CREATE TABLE team_evolution_scores (
 evaluation_id VARCHAR(36) PRIMARY KEY,
 request_key VARCHAR(64) NOT NULL,
 fingerprint VARCHAR(64) NOT NULL,
 principal_digest VARCHAR(64) NOT NULL,
 proof_digest VARCHAR(64) NOT NULL,
 result_json MEDIUMTEXT NOT NULL,
 created_at TIMESTAMP(6) NOT NULL,
 CONSTRAINT fk_team_score_execution FOREIGN KEY(evaluation_id) REFERENCES team_evolution_executions(evaluation_id)
);
CREATE TABLE team_evolution_frontiers (
 contract_id BIGINT PRIMARY KEY,
 request_key VARCHAR(64) NOT NULL,
 fingerprint VARCHAR(64) NOT NULL,
 principal_digest VARCHAR(64) NOT NULL,
 result_json MEDIUMTEXT NOT NULL,
 created_at TIMESTAMP(6) NOT NULL,
 CONSTRAINT fk_team_frontier_contract FOREIGN KEY(contract_id) REFERENCES team_evolution_contracts(id)
);
