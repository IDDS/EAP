-- Existing and manually entered references remain unverified. No inferred backfill.
-- Observation metadata may change or expire; keep the creation-time snapshot.
alter table eap_evaluation_dataset_items add column source_observation_id bigint;
alter table eap_evaluation_dataset_items add column source_endpoint_key varchar(128);
alter table eap_evaluation_dataset_items add column source_observed_at timestamp(6);
alter table eap_evaluation_dataset_items add column source_checked_at timestamp(6);
