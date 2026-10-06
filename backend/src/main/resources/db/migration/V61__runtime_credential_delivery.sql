ALTER TABLE runtime_connections ADD COLUMN credential_serial BIGINT NOT NULL DEFAULT 0;
ALTER TABLE runtime_connections ADD COLUMN delivery_serial BIGINT;
ALTER TABLE runtime_connections ADD COLUMN identity_expires_at TIMESTAMP(6);
ALTER TABLE runtime_connections ADD COLUMN credential_revision VARCHAR(36);
ALTER TABLE runtime_connections ADD COLUMN automatic_delivery BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE runtime_connections ADD COLUMN delivery_operation VARCHAR(36);
ALTER TABLE runtime_connections ADD COLUMN delivery_revision VARCHAR(36);
ALTER TABLE runtime_connections ADD COLUMN delivery_expires_at TIMESTAMP(6);
ALTER TABLE runtime_connections ADD COLUMN last_delivery_at TIMESTAMP(6);
CREATE TABLE runtime_delivery_requests (
 instance_key VARCHAR(128) NOT NULL,
 request_key VARCHAR(36) NOT NULL,
 identity_epoch VARCHAR(36) NOT NULL,
 created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 PRIMARY KEY(instance_key, request_key)
);
