alter table model_invocation_events add column observation_source varchar(32) not null default 'TELEMETRY_API';
alter table model_invocation_events add column governance_covered boolean not null default false;

create index idx_model_invocation_source_observed on model_invocation_events(observation_source, observed_at);
