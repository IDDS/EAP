create table tool_invocation_events (
    id bigint auto_increment primary key,
    invocation_key varchar(128) not null unique,
    trace_id varchar(128),
    span_id varchar(128),
    parent_span_id varchar(128),
    agent_id bigint,
    endpoint_key varchar(128),
    runtime_instance_key varchar(128),
    task_id bigint,
    observation_source varchar(32) not null,
    tool_kind varchar(32) not null,
    tool_name varchar(256) not null,
    mcp_server_name varchar(256),
    status varchar(32) not null,
    duration_ms bigint not null,
    input_observation_key varchar(128),
    output_observation_key varchar(128),
    attributes_json text,
    observed_at timestamp(6) not null,
    created_at timestamp(6) not null,
    updated_at timestamp(6) not null
);

create index idx_tool_invocation_agent on tool_invocation_events(agent_id, observed_at);
create index idx_tool_invocation_trace on tool_invocation_events(trace_id, observed_at);
create index idx_tool_invocation_name on tool_invocation_events(tool_name, observed_at);
create index idx_tool_invocation_source on tool_invocation_events(observation_source, observed_at);
