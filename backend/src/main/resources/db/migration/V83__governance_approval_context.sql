-- Old decisions retain their original outcome; their approval context is explicitly unknown.
ALTER TABLE governance_preflight_decisions ADD COLUMN policy_context_digest CHAR(64);
ALTER TABLE governance_preflight_decisions ADD COLUMN revision BIGINT NOT NULL DEFAULT 0;
