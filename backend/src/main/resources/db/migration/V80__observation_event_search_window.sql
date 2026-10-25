-- Index existing committed events only. No enrollment, log backfill or business status changes.
CREATE INDEX ix_observation_event_window ON observation_operation_events(created_at,operation_id,event_kind);
