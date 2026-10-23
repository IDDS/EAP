-- Diagnostic choices apply only to newly registered trials. Never enroll old operations.
CREATE TABLE diagnostic_policy_versions (
 version BIGINT PRIMARY KEY,
 algorithm VARCHAR(32) NOT NULL,
 sample_basis_points INT NOT NULL,
 principal_digest VARCHAR(64) NOT NULL,
 created_at TIMESTAMP(6) NOT NULL
);
INSERT INTO diagnostic_policy_versions VALUES (1,'SHA256_TRACE_V1',10000,'SYSTEM_DEFAULT',CURRENT_TIMESTAMP);
CREATE TABLE diagnostic_policy_head (
 id INT PRIMARY KEY,
 current_version BIGINT NOT NULL,
 CONSTRAINT fk_diagnostic_policy_head FOREIGN KEY(current_version) REFERENCES diagnostic_policy_versions(version)
);
INSERT INTO diagnostic_policy_head VALUES (1,1);
CREATE TABLE experiment_diagnostic_decisions (
 trial_id BIGINT PRIMARY KEY,
 policy_version BIGINT NOT NULL,
 runtime_id BIGINT NOT NULL,
 agent_id BIGINT NOT NULL,
 instance_key VARCHAR(128) NOT NULL,
 endpoint VARCHAR(512) NOT NULL,
 actor_uid VARCHAR(64) NOT NULL,
 actor_template VARCHAR(128) NOT NULL,
 trace_id VARCHAR(32) NOT NULL UNIQUE,
 identity_confirmed BOOLEAN NOT NULL,
 selected BOOLEAN,
 reason_code VARCHAR(64) NOT NULL,
 limit_dropped BIGINT NOT NULL DEFAULT 0,
 created_at TIMESTAMP(6) NOT NULL,
 CONSTRAINT fk_diagnostic_trial FOREIGN KEY(trial_id) REFERENCES agent_experiment_trials(id),
 CONSTRAINT fk_diagnostic_version FOREIGN KEY(policy_version) REFERENCES diagnostic_policy_versions(version)
);
CREATE TABLE experiment_diagnostic_receipts (
 trial_id BIGINT NOT NULL,
 span_id VARCHAR(16) NOT NULL,
 disposition VARCHAR(32) NOT NULL,
 reason_code VARCHAR(64) NOT NULL,
 attempts BIGINT NOT NULL DEFAULT 1,
 created_at TIMESTAMP(6) NOT NULL,
 PRIMARY KEY(trial_id,span_id),
 CONSTRAINT fk_diagnostic_receipt FOREIGN KEY(trial_id) REFERENCES experiment_diagnostic_decisions(trial_id)
);
