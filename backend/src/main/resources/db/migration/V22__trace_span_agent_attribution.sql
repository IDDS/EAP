alter table trace_span_observations add column agent_id bigint;

create index idx_trace_span_agent on trace_span_observations(agent_id, observed_at);
