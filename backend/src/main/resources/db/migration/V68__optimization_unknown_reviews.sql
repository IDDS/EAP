-- Append-only decisions. Original roots, trials and paid budget records are never rewritten.
CREATE TABLE agent_optimization_unknown_reviews (
 run_id BIGINT PRIMARY KEY,
 request_key VARCHAR(64) NOT NULL UNIQUE,
 request_fingerprint VARCHAR(64) NOT NULL,
 evidence_digest VARCHAR(64) NOT NULL,
 evidence_json MEDIUMTEXT NOT NULL,
 principal_digest VARCHAR(64) NOT NULL,
 decision VARCHAR(32) NOT NULL,
 reason_code VARCHAR(64) NOT NULL,
 created_at TIMESTAMP(6) NOT NULL,
 CONSTRAINT fk_unknown_review_root FOREIGN KEY(run_id) REFERENCES agent_optimizations(id)
);
