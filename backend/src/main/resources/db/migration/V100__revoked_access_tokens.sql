CREATE TABLE revoked_access_tokens (
    token_hash VARCHAR(64) NOT NULL PRIMARY KEY,
    revoked_at_ms BIGINT NOT NULL,
    expires_at_ms BIGINT
);
