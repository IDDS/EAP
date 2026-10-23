create table governance_data_events (
    id bigint auto_increment primary key,
    event_key varchar(128) not null unique,
    agent_id bigint,
    endpoint_key varchar(128),
    task_id bigint,
    trace_id varchar(128),
    direction varchar(32) not null,
    data_category varchar(128) not null,
    sensitivity varchar(32) not null,
    action varchar(32) not null,
    internal_data boolean not null,
    company_secret boolean not null,
    token_count bigint not null,
    payload_bytes bigint not null,
    policy_id bigint,
    policy_rule varchar(256),
    observed_at timestamp(6) not null,
    created_at timestamp(6) not null,
    constraint fk_governance_data_agent foreign key (agent_id) references agents(id),
    constraint fk_governance_data_task foreign key (task_id) references agent_tasks(id),
    constraint fk_governance_data_policy foreign key (policy_id) references governance_policies(id)
);

create index idx_governance_data_observed on governance_data_events(observed_at);
create index idx_governance_data_agent_observed on governance_data_events(agent_id, observed_at);
create index idx_governance_data_endpoint_observed on governance_data_events(endpoint_key, observed_at);
create index idx_governance_data_action_observed on governance_data_events(action, observed_at);
