create table model_invocation_events (
    id bigint auto_increment primary key,
    event_key varchar(128) not null unique,
    trace_id varchar(128),
    span_id varchar(128),
    agent_id bigint,
    endpoint_key varchar(128),
    runtime_instance_key varchar(128),
    task_id bigint,
    provider varchar(128) not null,
    model_name varchar(128) not null,
    status varchar(32) not null,
    input_tokens bigint not null,
    output_tokens bigint not null,
    cached_input_tokens bigint not null,
    reasoning_tokens bigint not null,
    latency_ms bigint not null,
    money_cost decimal(18, 6) not null,
    currency varchar(16) not null,
    tool_call_count int not null,
    mcp_server_count int not null,
    error_type varchar(128),
    observed_at timestamp(6) not null,
    created_at timestamp(6) not null,
    constraint fk_model_invocation_agent foreign key (agent_id) references agents(id),
    constraint fk_model_invocation_task foreign key (task_id) references agent_tasks(id)
);

create index idx_model_invocation_observed on model_invocation_events(observed_at);
create index idx_model_invocation_agent_observed on model_invocation_events(agent_id, observed_at);
create index idx_model_invocation_endpoint_observed on model_invocation_events(endpoint_key, observed_at);
create index idx_model_invocation_model_observed on model_invocation_events(provider, model_name, observed_at);
