CREATE TABLE execution_link_guard (id BIGINT PRIMARY KEY);
INSERT INTO execution_link_guard(id) VALUES (1);

CREATE TABLE execution_links (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    link_id VARCHAR(36) NOT NULL UNIQUE,
    relation_type VARCHAR(32) NOT NULL,
    source_stream VARCHAR(64) NOT NULL,
    source_run VARCHAR(36) NOT NULL,
    source_agent_id BIGINT NOT NULL,
    source_runtime_key VARCHAR(128) NOT NULL,
    target_stream VARCHAR(64) NOT NULL,
    target_run VARCHAR(36) NOT NULL,
    target_agent_id BIGINT,
    target_runtime_key VARCHAR(128),
    artifact_digest VARCHAR(64),
    artifact_size BIGINT,
    artifact_language VARCHAR(16),
    offered_ms BIGINT NOT NULL,
    confirmed_ms BIGINT
);
CREATE INDEX idx_execution_link_source ON execution_links(source_stream, id);
CREATE INDEX idx_execution_link_target ON execution_links(target_stream, id);
