-- Optional, append-once observation. Never backfill historical task locations.
CREATE TABLE observation_runtime_placements (
 operation_id BIGINT NOT NULL,
 phase VARCHAR(32) NOT NULL,
 requested_at TIMESTAMP(6) NOT NULL,
 snapshot_json LONGTEXT,
 completed_at TIMESTAMP(6),
 PRIMARY KEY(operation_id, phase),
 CONSTRAINT fk_runtime_placement_operation FOREIGN KEY(operation_id)
  REFERENCES observation_operations(id) ON DELETE CASCADE
);
