CREATE TABLE runtime_connections (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    revision BIGINT NOT NULL DEFAULT 0,
    instance_key VARCHAR(128) NOT NULL UNIQUE,
    request_key VARCHAR(36),
    generation VARCHAR(36),
    expires_at TIMESTAMP(6) NULL,
    verified_at TIMESTAMP(6) NULL,
    gateway_at TIMESTAMP(6) NULL,
    gateway_trace VARCHAR(128),
    last_message VARCHAR(200),
    trace_at TIMESTAMP(6) NULL
);
