create table trace_span_observations (
    id bigint auto_increment primary key,
    trace_id varchar(128) not null,
    span_id varchar(128) not null,
    parent_span_id varchar(128),
    endpoint_key varchar(128) not null,
    span_name varchar(256) not null,
    span_kind varchar(32) not null,
    operation varchar(128),
    tool_name varchar(256),
    mcp_server_name varchar(256),
    rag_source varchar(512),
    status varchar(32),
    duration_ms bigint not null,
    attributes_json text,
    observed_at timestamp(6) not null,
    created_at timestamp(6) not null,
    constraint uk_trace_span_identity unique (trace_id, span_id)
);

create index idx_trace_span_observed on trace_span_observations(observed_at);
create index idx_trace_span_kind on trace_span_observations(span_kind, observed_at);
create index idx_trace_span_endpoint on trace_span_observations(endpoint_key, observed_at);
