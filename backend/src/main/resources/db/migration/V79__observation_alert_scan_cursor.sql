-- Scheduler metadata only. No historical enrollment, alert backfill or business updates.
CREATE TABLE observation_alert_scan_cursor (
 id INT PRIMARY KEY,
 sweep_number BIGINT NOT NULL,
 upper_operation_id BIGINT NOT NULL,
 last_operation_id BIGINT NOT NULL,
 cursor_revision BIGINT NOT NULL,
 processed_count BIGINT NOT NULL,
 failure_count BIGINT NOT NULL,
 last_failure_operation_id BIGINT,
 last_failure_code VARCHAR(32),
 last_failure_at TIMESTAMP(6),
 changed_at TIMESTAMP(6) NOT NULL
);
INSERT INTO observation_alert_scan_cursor
 (id,sweep_number,upper_operation_id,last_operation_id,cursor_revision,processed_count,failure_count,changed_at)
 VALUES(1,0,0,0,0,0,0,CURRENT_TIMESTAMP(6));
