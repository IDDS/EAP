create table knowledge_interactions (
    id bigint auto_increment primary key,
    interaction_key varchar(128) not null unique,
    trace_id varchar(128),
    agent_id bigint,
    endpoint_key varchar(128),
    task_id bigint,
    input_type varchar(128) not null,
    input_source_count int not null,
    input_token_count bigint not null,
    input_summary varchar(2048),
    input_refs_json text,
    output_type varchar(128),
    output_source_count int not null,
    output_token_count bigint not null,
    output_summary varchar(2048),
    output_refs_json text,
    status varchar(32) not null,
    adopted boolean not null,
    reused boolean not null,
    quality_score int,
    process_step_count int not null,
    process_token_count bigint not null,
    process_latency_ms bigint not null,
    started_at timestamp(6),
    completed_at timestamp(6),
    created_at timestamp(6) not null,
    updated_at timestamp(6) not null,
    constraint fk_knowledge_interaction_agent foreign key (agent_id) references agents(id),
    constraint fk_knowledge_interaction_task foreign key (task_id) references agent_tasks(id)
);

create table knowledge_trace_steps (
    id bigint auto_increment primary key,
    interaction_id bigint not null,
    step_order int not null,
    step_type varchar(32) not null,
    name varchar(256) not null,
    status varchar(32) not null,
    source_count int not null,
    input_tokens bigint not null,
    output_tokens bigint not null,
    latency_ms bigint not null,
    refs_json text,
    metadata_json text,
    observed_at timestamp(6) not null,
    constraint fk_knowledge_trace_interaction foreign key (interaction_id) references knowledge_interactions(id),
    constraint uk_knowledge_trace_step unique (interaction_id, step_order)
);

create index idx_knowledge_interactions_created on knowledge_interactions(created_at);
create index idx_knowledge_interactions_agent_created on knowledge_interactions(agent_id, created_at);
create index idx_knowledge_interactions_status on knowledge_interactions(status, created_at);
create index idx_knowledge_trace_interaction_order on knowledge_trace_steps(interaction_id, step_order);
