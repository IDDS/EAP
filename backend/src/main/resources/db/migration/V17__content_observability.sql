create table content_observations (
    id bigint auto_increment primary key,
    observation_key varchar(128) not null unique,
    event_key varchar(128),
    trace_id varchar(128),
    span_id varchar(128),
    agent_id bigint,
    endpoint_key varchar(128),
    task_id bigint,
    provider varchar(128),
    model_name varchar(128),
    direction varchar(32) not null,
    capture_mode varchar(32) not null,
    content_hash varchar(64) not null,
    content_bytes bigint not null,
    estimated_tokens bigint not null,
    content_json text,
    data_category varchar(128),
    sensitivity varchar(32),
    governance_action varchar(32),
    redaction_applied boolean not null,
    observed_at timestamp(6) not null,
    created_at timestamp(6) not null
);

create index idx_content_observation_trace on content_observations(trace_id, observed_at);
create index idx_content_observation_agent on content_observations(agent_id, observed_at);
create index idx_content_observation_direction on content_observations(direction, observed_at);
