create table management_value_events (
    id bigint auto_increment primary key,
    event_key varchar(128) not null unique,
    agent_id bigint,
    endpoint_key varchar(128),
    task_id bigint,
    knowledge_interaction_id bigint,
    value_type varchar(128) not null,
    description varchar(2048),
    validated_by varchar(128) not null,
    benefit_money decimal(18, 6) not null,
    currency varchar(16) not null,
    observed_at timestamp(6) not null,
    created_at timestamp(6) not null,
    constraint fk_management_value_agent foreign key (agent_id) references agents(id),
    constraint fk_management_value_task foreign key (task_id) references agent_tasks(id),
    constraint fk_management_value_knowledge foreign key (knowledge_interaction_id) references knowledge_interactions(id)
);

create index idx_management_value_observed on management_value_events(observed_at);
create index idx_management_value_agent_observed on management_value_events(agent_id, observed_at);
create index idx_management_value_endpoint_observed on management_value_events(endpoint_key, observed_at);
