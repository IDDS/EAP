create table eap_evaluation_jobs (
    id bigint not null auto_increment,
    job_key varchar(128) not null,
    requested_limit int not null,
    status varchar(32) not null,
    scanned int not null default 0,
    evaluated int not null default 0,
    skipped int not null default 0,
    failed int not null default 0,
    error_message varchar(2048),
    created_at timestamp(6) not null,
    started_at timestamp(6),
    completed_at timestamp(6),
    updated_at timestamp(6) not null,
    primary key (id),
    unique key uk_eap_evaluation_jobs_key (job_key)
);

create index idx_eap_evaluation_jobs_status_time
    on eap_evaluation_jobs(status, created_at);
