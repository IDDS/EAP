create table agent_capabilities (
    id bigint primary key auto_increment,
    agent_id bigint not null,
    capability varchar(128) not null,
    name varchar(128) not null,
    description varchar(512),
    created_at timestamp(6) not null,
    updated_at timestamp(6) not null,
    constraint fk_agent_capabilities_agent foreign key (agent_id) references agents(id)
);

create table agent_runtime_instances (
    id bigint primary key auto_increment,
    agent_id bigint not null,
    instance_key varchar(128) not null,
    endpoint varchar(512) not null,
    protocol varchar(32) not null,
    status varchar(32) not null,
    load_score int not null,
    last_heartbeat_at timestamp(6),
    created_at timestamp(6) not null,
    updated_at timestamp(6) not null,
    constraint fk_agent_runtime_instances_agent foreign key (agent_id) references agents(id),
    constraint uk_agent_runtime_instances_key unique (instance_key)
);

insert into agent_capabilities (agent_id, capability, name, description, created_at, updated_at)
values
(1, 'finance.analysis', '财务经营分析', '分析财务指标、费用异常和经营波动。', current_timestamp(6), current_timestamp(6)),
(2, 'hr.policy.qa', '人事制度问答', '回答员工制度、假期和入职流程问题。', current_timestamp(6), current_timestamp(6)),
(3, 'ops.incident.triage', '运维事件分诊', '分析告警、识别影响面并生成处置建议。', current_timestamp(6), current_timestamp(6));

insert into agent_runtime_instances (agent_id, instance_key, endpoint, protocol, status, load_score, last_heartbeat_at, created_at, updated_at)
values
(1, 'fin-analyst-local-1', 'http://fin-agent.local:9001', 'HTTP', 'ONLINE', 18, current_timestamp(6), current_timestamp(6), current_timestamp(6)),
(2, 'hr-assistant-local-1', 'http://hr-agent.local:9002', 'HTTP', 'ONLINE', 32, current_timestamp(6), current_timestamp(6), current_timestamp(6)),
(3, 'ops-incident-local-1', 'http://ops-agent.local:9003', 'HTTP', 'ONLINE', 44, current_timestamp(6), current_timestamp(6), current_timestamp(6));

create index idx_agent_capabilities_capability on agent_capabilities(capability);
create index idx_agent_capabilities_agent on agent_capabilities(agent_id);
create index idx_agent_runtime_agent_status on agent_runtime_instances(agent_id, status);

