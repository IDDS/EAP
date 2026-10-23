create table agent_experiment_admission(id bigint primary key);
insert into agent_experiment_admission(id) values(1);
create table agent_experiments (
 id bigint auto_increment primary key, agent_id bigint not null, instance_key varchar(128) not null,
 actor_uid varchar(64) not null, template varchar(128) not null,
 endpoint varchar(512) not null, image_ref varchar(512) not null, deployment_version integer not null,
 submission_key varchar(64) not null unique, request_fingerprint varchar(64) not null,
 profile_digest varchar(64) not null, profile_json text not null, samples_json text not null,
 max_tokens integer not null, requested_by varchar(128) not null, status varchar(32) not null,
 created_at timestamp(6) not null, updated_at timestamp(6) not null,
 constraint fk_experiment_agent foreign key(agent_id) references agents(id)
);
create table agent_experiment_variants (
 id bigint auto_increment primary key, experiment_id bigint not null, variant_name varchar(16) not null,
 config_digest varchar(64) not null, system_prompt text not null,
 constraint fk_variant_experiment foreign key(experiment_id) references agent_experiments(id),
 constraint uq_experiment_variant unique(experiment_id,variant_name)
);
create table agent_experiment_trials (
 id bigint auto_increment primary key, experiment_id bigint not null, variant_id bigint not null,
 sample_index integer not null, status varchar(32) not null, task_key varchar(96) not null unique,
 trace_id varchar(32) not null unique, output_text text, finish_reason varchar(32),
 input_tokens bigint, output_tokens bigint, usage_source varchar(32) not null,
 latency_ms bigint, end_to_end_ms bigint, queue_ms bigint, score integer,
 reason_code varchar(64), started_at timestamp(6), finished_at timestamp(6),
 constraint fk_trial_experiment foreign key(experiment_id) references agent_experiments(id),
 constraint fk_trial_variant foreign key(variant_id) references agent_experiment_variants(id),
 constraint uq_experiment_trial unique(experiment_id,variant_id,sample_index)
);
create index idx_experiment_status on agent_experiments(status,id);
create index idx_trial_status on agent_experiment_trials(experiment_id,status,id);
