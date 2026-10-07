-- New runs only. Historical optimizer roots, operations, samples and budgets are never backfilled.
create table agent_optimization_protocols (
 run_id bigint primary key, version integer not null, principal_digest varchar(64) not null,
 planned_calls integer not null, planned_output_tokens bigint not null,
 holdout_samples integer not null, holdout_groups integer not null,
 constraint fk_optimization_protocol_run foreign key(run_id) references agent_optimizations(id)
);
create table agent_optimization_holdout_batches (
 run_id bigint not null, source varchar(16) not null, ordinal integer not null,
 candidate_digest varchar(64) not null, operation_key varchar(64) not null,
 sample_ids_json text not null, evaluation_id bigint,
 primary key(run_id,source,ordinal),
 constraint fk_optimization_batch_protocol foreign key(run_id) references agent_optimization_protocols(run_id),
 constraint fk_optimization_batch_evaluation foreign key(evaluation_id) references agent_optimization_evaluations(id)
);
