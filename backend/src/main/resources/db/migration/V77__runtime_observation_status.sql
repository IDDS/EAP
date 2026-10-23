-- Prospective task/chat registration only. No historical traces or identities are inferred.
ALTER TABLE observation_operations ADD COLUMN runtime_status VARCHAR(32);
ALTER TABLE observation_operations ADD COLUMN dispatch_deadline TIMESTAMP(6);
