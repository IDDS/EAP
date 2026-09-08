create table eap_evaluation_calibration_runs (
    run_id varchar(64) primary key,
    dataset_key varchar(128) not null,
    dataset_version varchar(64) not null,
    requested_limit integer not null,
    total_items integer not null,
    evaluated_items integer not null,
    failed_items integer not null,
    calculated_at timestamp(6) not null,
    snapshot_json longtext not null
);

create index idx_calibration_runs_dataset_time
    on eap_evaluation_calibration_runs (dataset_key, calculated_at, run_id);
