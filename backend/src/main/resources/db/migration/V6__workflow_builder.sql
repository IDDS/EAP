create table workflow_definitions (
    id bigint primary key auto_increment,
    code varchar(64) not null,
    name varchar(128) not null,
    description varchar(512),
    status varchar(32) not null,
    created_at timestamp(6) not null,
    updated_at timestamp(6) not null,
    constraint uk_workflow_definitions_code unique (code)
);

create table workflow_nodes (
    id bigint primary key auto_increment,
    workflow_id bigint not null,
    node_key varchar(64) not null,
    label varchar(128) not null,
    node_type varchar(32) not null,
    agent_id bigint,
    capability varchar(128),
    position_x int not null,
    position_y int not null,
    config_json json,
    created_at timestamp(6) not null,
    updated_at timestamp(6) not null,
    constraint fk_workflow_nodes_workflow foreign key (workflow_id) references workflow_definitions(id),
    constraint fk_workflow_nodes_agent foreign key (agent_id) references agents(id),
    constraint uk_workflow_nodes_key unique (workflow_id, node_key)
);

create table workflow_edges (
    id bigint primary key auto_increment,
    workflow_id bigint not null,
    source_node_key varchar(64) not null,
    target_node_key varchar(64) not null,
    condition_expression varchar(128),
    created_at timestamp(6) not null,
    updated_at timestamp(6) not null,
    constraint fk_workflow_edges_workflow foreign key (workflow_id) references workflow_definitions(id)
);

insert into workflow_definitions (code, name, description, status, created_at, updated_at)
values
('INCIDENT-FLOW', '故障处置工作流', '通过拖拽节点构建多智能体协作流程。', 'DRAFT', current_timestamp(6), current_timestamp(6));

insert into workflow_nodes (workflow_id, node_key, label, node_type, agent_id, capability, position_x, position_y, config_json, created_at, updated_at)
values
(1, 'start', '开始', 'START', null, null, 80, 180, '{}', current_timestamp(6), current_timestamp(6)),
(1, 'triage', '告警分诊', 'AGENT', 3, 'ops.incident.triage', 280, 150, '{"safety":"required"}', current_timestamp(6), current_timestamp(6)),
(1, 'impact', '影响评估', 'AGENT', 1, 'finance.analysis', 500, 150, '{"safety":"required"}', current_timestamp(6), current_timestamp(6)),
(1, 'report', '生成报告', 'ARTIFACT', 3, 'ops.incident.triage', 720, 180, '{"artifactRequired":true}', current_timestamp(6), current_timestamp(6));

insert into workflow_edges (workflow_id, source_node_key, target_node_key, condition_expression, created_at, updated_at)
values
(1, 'start', 'triage', 'always', current_timestamp(6), current_timestamp(6)),
(1, 'triage', 'impact', 'severity >= medium', current_timestamp(6), current_timestamp(6)),
(1, 'impact', 'report', 'approved', current_timestamp(6), current_timestamp(6));

create index idx_workflow_nodes_workflow on workflow_nodes(workflow_id);
create index idx_workflow_edges_workflow on workflow_edges(workflow_id);

