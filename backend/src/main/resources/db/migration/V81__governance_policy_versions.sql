-- Preserve existing policy behavior. Revision zero does not invent historical publications.
ALTER TABLE governance_policies ADD COLUMN revision BIGINT NOT NULL DEFAULT 0;
CREATE TABLE governance_policy_versions (
 policy_id BIGINT NOT NULL,
 revision BIGINT NOT NULL,
 change_kind VARCHAR(24) NOT NULL,
 restored_from_revision BIGINT,
 snapshot_json LONGTEXT NOT NULL,
 snapshot_digest CHAR(64) NOT NULL,
 principal_digest CHAR(64) NOT NULL,
 recorded_at TIMESTAMP(6) NOT NULL,
 PRIMARY KEY(policy_id, revision),
 CONSTRAINT ck_governance_policy_revision CHECK(revision >= 0),
 CONSTRAINT ck_governance_policy_change CHECK(change_kind IN ('CREATED','BASELINE','UPDATED','ROLLED_BACK'))
);
