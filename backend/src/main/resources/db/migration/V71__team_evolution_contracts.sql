-- Prospective metadata contracts only. No historical enrollment or budget allocation.
CREATE TABLE team_evolution_admission (id INT PRIMARY KEY);
INSERT INTO team_evolution_admission VALUES (1);
CREATE TABLE team_evolution_contracts (
 id BIGINT AUTO_INCREMENT PRIMARY KEY,
 submission_key VARCHAR(64) NOT NULL UNIQUE,
 fingerprint VARCHAR(64) NOT NULL,
 principal_digest VARCHAR(64) NOT NULL,
 protocol_digest VARCHAR(64) NOT NULL,
 dataset_digest VARCHAR(64) NOT NULL,
 validation_digest VARCHAR(64) NOT NULL,
 protocol_json TEXT NOT NULL,
 dataset_json MEDIUMTEXT NOT NULL,
 baseline_id VARCHAR(64) NOT NULL,
 max_candidates INT NOT NULL,
 max_attempts INT NOT NULL,
 max_generation INT NOT NULL,
 minimum_quality_bps INT NOT NULL,
 attempts INT NOT NULL DEFAULT 0,
 created_at TIMESTAMP(6) NOT NULL
);
CREATE TABLE team_evolution_candidates (
 contract_id BIGINT NOT NULL,
 candidate_id VARCHAR(64) NOT NULL,
 parent_id VARCHAR(64),
 generation INT NOT NULL,
 changed_role VARCHAR(64),
 configuration_json MEDIUMTEXT NOT NULL,
 created_at TIMESTAMP(6) NOT NULL,
 PRIMARY KEY(contract_id,candidate_id),
 CONSTRAINT fk_team_candidate_contract FOREIGN KEY(contract_id) REFERENCES team_evolution_contracts(id),
 CONSTRAINT fk_team_candidate_parent FOREIGN KEY(contract_id,parent_id) REFERENCES team_evolution_candidates(contract_id,candidate_id)
);
CREATE TABLE team_evolution_attempts (
 contract_id BIGINT NOT NULL,
 operation_key VARCHAR(64) NOT NULL,
 fingerprint VARCHAR(64) NOT NULL,
 candidate_id VARCHAR(64),
 disposition VARCHAR(16) NOT NULL,
 reason_code VARCHAR(64),
 created_at TIMESTAMP(6) NOT NULL,
 PRIMARY KEY(contract_id,operation_key),
 CONSTRAINT fk_team_attempt_contract FOREIGN KEY(contract_id) REFERENCES team_evolution_contracts(id),
 CONSTRAINT fk_team_attempt_candidate FOREIGN KEY(contract_id,candidate_id) REFERENCES team_evolution_candidates(contract_id,candidate_id)
);
CREATE TABLE team_evolution_evaluations (
 id VARCHAR(36) PRIMARY KEY,
 contract_id BIGINT NOT NULL,
 candidate_id VARCHAR(64) NOT NULL,
 operation_key VARCHAR(64) NOT NULL,
 fingerprint VARCHAR(64) NOT NULL,
 state VARCHAR(32) NOT NULL,
 created_at TIMESTAMP(6) NOT NULL,
 UNIQUE(contract_id,operation_key),
 UNIQUE(contract_id,candidate_id),
 CONSTRAINT fk_team_evaluation_candidate FOREIGN KEY(contract_id,candidate_id) REFERENCES team_evolution_candidates(contract_id,candidate_id)
);
CREATE TABLE team_evolution_slots (
 evaluation_id VARCHAR(36) NOT NULL,
 sample_id VARCHAR(64) NOT NULL,
 role_name VARCHAR(64) NOT NULL,
 role_ordinal INT NOT NULL,
 operation_key VARCHAR(128) NOT NULL UNIQUE,
 trace_id VARCHAR(32) NOT NULL UNIQUE,
 state VARCHAR(32) NOT NULL,
 PRIMARY KEY(evaluation_id,sample_id,role_name),
 CONSTRAINT fk_team_slot_evaluation FOREIGN KEY(evaluation_id) REFERENCES team_evolution_evaluations(id)
);
