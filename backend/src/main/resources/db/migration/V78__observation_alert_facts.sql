-- Explicit metadata reconciliation only. No historical enrollment or business/status/usage updates.
-- Deliberately no FK to business rows: their deletion must not erase audit or block business work.
CREATE TABLE observation_alert_heads (
 operation_id BIGINT PRIMARY KEY,
 fact_revision BIGINT NOT NULL,
 fact_digest CHAR(64) NOT NULL,
 evaluated_at TIMESTAMP(6) NOT NULL
);
CREATE TABLE observation_alerts (
 id BIGINT AUTO_INCREMENT PRIMARY KEY,
 operation_id BIGINT NOT NULL,
 agent_id BIGINT,
 entry_kind VARCHAR(32) NOT NULL,
 alert_kind VARCHAR(32) NOT NULL,
 fact_revision BIGINT NOT NULL,
 fact_digest CHAR(64) NOT NULL,
 coverage VARCHAR(40) NOT NULL,
 execution_status VARCHAR(32) NOT NULL,
 lifecycle_state VARCHAR(24) NOT NULL,
 created_at TIMESTAMP(6) NOT NULL,
 changed_at TIMESTAMP(6) NOT NULL,
 CONSTRAINT uq_observation_alert_fact UNIQUE(operation_id,alert_kind,fact_revision)
);
CREATE INDEX ix_observation_alert_operation ON observation_alerts(operation_id,id);
CREATE TABLE observation_alert_audit (
 alert_id BIGINT NOT NULL,
 event_kind VARCHAR(24) NOT NULL,
 principal_digest CHAR(64) NOT NULL,
 created_at TIMESTAMP(6) NOT NULL,
 PRIMARY KEY(alert_id,event_kind),
 CONSTRAINT fk_observation_alert_audit FOREIGN KEY(alert_id) REFERENCES observation_alerts(id)
);
