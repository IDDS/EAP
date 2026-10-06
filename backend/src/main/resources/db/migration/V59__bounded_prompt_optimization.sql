create table agent_optimization_admission(id bigint primary key);
insert into agent_optimization_admission(id) values(1);
create table agent_optimizations (
 id bigint auto_increment primary key, agent_id bigint not null, instance_key varchar(128) not null,
 submission_key varchar(64) not null unique, fingerprint varchar(64) not null,
 profile_json text not null, dataset_json text not null, dataset_digest varchar(64) not null,
 manual_prompt text not null,
 public_samples_confirmed boolean not null default false, reflection_use_confirmed boolean not null default false, engine varchar(16) not null, engine_version varchar(64) not null,
 max_tokens integer not null, max_metric_calls integer not null, max_calls integer not null,
 max_output_tokens bigint not null, status varchar(24) not null, requested_by varchar(128) not null,
 claim_hash varchar(64), claim_deadline timestamp(6), chosen_digest varchar(64),
 recommendation varchar(64), reason_code varchar(64),
 created_at timestamp(6) not null, started_at timestamp(6), updated_at timestamp(6) not null,
 constraint fk_optimization_agent foreign key(agent_id) references agents(id)
);
create table agent_optimization_evaluations (
 id bigint auto_increment primary key, run_id bigint not null, operation_key varchar(64) not null unique,
 candidate_digest varchar(64) not null, prompt_text text not null, split varchar(16) not null,
 source varchar(16) not null, sample_ids_json text not null, experiment_id bigint not null,
 created_at timestamp(6) not null,
 constraint fk_optimization_eval_run foreign key(run_id) references agent_optimizations(id),
 constraint fk_optimization_eval_experiment foreign key(experiment_id) references agent_experiments(id)
);
create table agent_optimization_reflections (
 id bigint auto_increment primary key, run_id bigint not null, ordinal integer not null,
 operation_key varchar(128) not null unique, trace_id varchar(32) not null unique,
 fingerprint varchar(64) not null, prompt_text text not null, candidate_text text,
 status varchar(24) not null, reason_code varchar(64), created_at timestamp(6) not null,
 constraint fk_optimization_reflection_run foreign key(run_id) references agent_optimizations(id),
 constraint uq_optimization_reflection unique(run_id,ordinal)
);
alter table agent_experiments add column optimization_run_id bigint;
alter table agent_experiments add constraint fk_experiment_optimization foreign key(optimization_run_id) references agent_optimizations(id);
create index idx_optimization_status on agent_optimizations(status,id);
