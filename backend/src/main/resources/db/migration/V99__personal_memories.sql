CREATE TABLE personal_memories (
    memory_id VARCHAR(36) NOT NULL PRIMARY KEY,
    owner_hash VARCHAR(64) NOT NULL,
    request_key VARCHAR(36) NOT NULL,
    content VARCHAR(2048),
    expires_ms BIGINT,
    created_ms BIGINT NOT NULL,
    revoked_ms BIGINT,
    CONSTRAINT uq_personal_memory_request UNIQUE (owner_hash, request_key)
);
CREATE INDEX idx_personal_memory_owner_created ON personal_memories(owner_hash, created_ms, memory_id);
