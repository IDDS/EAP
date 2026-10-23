alter table agents
    add column record_origin varchar(32) not null default 'PRODUCTION';

update agents
set record_origin = 'DEMO'
where (code = 'FIN-ANALYST' and name = '财务分析智能体')
   or (code = 'HR-ASSISTANT' and name = '人力资源助手')
   or (code = 'OPS-INCIDENT' and name = '运维事件智能体');

create index idx_agents_record_origin on agents(record_origin, lifecycle_status);

create table agent_work_items (
    id bigint not null auto_increment,
    item_key varchar(160) not null,
    item_type varchar(64) not null,
    title varchar(256) not null,
    description varchar(2048),
    severity varchar(8) not null,
    status varchar(32) not null,
    agent_id bigint,
    endpoint_key varchar(128),
    runtime_instance_key varchar(128),
    source_type varchar(64) not null,
    source_id varchar(160),
    organization_scope varchar(256),
    assignee varchar(128),
    assigned_team varchar(128),
    evidence_basis varchar(32) not null,
    evidence_json text,
    occurrence_count int not null default 1,
    first_detected_at timestamp(6) not null,
    last_detected_at timestamp(6) not null,
    sla_due_at timestamp(6),
    resolution varchar(2048),
    resolution_evidence_json text,
    resolved_at timestamp(6),
    closed_at timestamp(6),
    created_at timestamp(6) not null,
    updated_at timestamp(6) not null,
    primary key (id),
    unique key uk_agent_work_items_key (item_key),
    constraint fk_agent_work_items_agent foreign key (agent_id) references agents(id)
);

create index idx_agent_work_items_queue
    on agent_work_items(status, severity, sla_due_at);
create index idx_agent_work_items_agent
    on agent_work_items(agent_id, status, updated_at);
create index idx_agent_work_items_endpoint
    on agent_work_items(endpoint_key, status, updated_at);

create table agent_work_item_events (
    id bigint not null auto_increment,
    work_item_id bigint not null,
    event_type varchar(64) not null,
    actor varchar(128) not null,
    from_status varchar(32),
    to_status varchar(32),
    note varchar(2048),
    evidence_json text,
    created_at timestamp(6) not null,
    primary key (id),
    constraint fk_agent_work_item_events_item foreign key (work_item_id) references agent_work_items(id)
);

create index idx_agent_work_item_events_item_time
    on agent_work_item_events(work_item_id, created_at);
