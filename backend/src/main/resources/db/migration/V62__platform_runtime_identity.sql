-- New platform RUNNER identities only. Existing templates and imported bindings are unchanged.
ALTER TABLE agent_runtime_instances ADD COLUMN platform_actor_uid VARCHAR(64);
ALTER TABLE agent_runtime_instances ADD COLUMN platform_actor_template VARCHAR(128);
CREATE TABLE platform_runtime_creations (
 agent_id BIGINT NOT NULL,
 request_key VARCHAR(64) NOT NULL,
 deployment_key VARCHAR(64) NOT NULL,
 instance_key VARCHAR(128) NOT NULL,
 actor_uid VARCHAR(64),
 revoked BOOLEAN NOT NULL DEFAULT FALSE,
 attempted BOOLEAN NOT NULL DEFAULT FALSE,
 delivery_request VARCHAR(36) NOT NULL,
 created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 PRIMARY KEY(agent_id, request_key),
 UNIQUE(instance_key)
);
