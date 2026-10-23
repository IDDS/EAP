create table agent_teams (
    id bigint primary key auto_increment,
    code varchar(64) not null,
    name varchar(128) not null,
    objective varchar(512),
    coordination_mode varchar(32) not null,
    workspace_policy varchar(128) not null,
    status varchar(32) not null,
    created_at timestamp(6) not null,
    updated_at timestamp(6) not null,
    constraint uk_agent_teams_code unique (code)
);

create table agent_team_members (
    id bigint primary key auto_increment,
    team_id bigint not null,
    agent_id bigint not null,
    role varchar(64) not null,
    responsibility varchar(128) not null,
    created_at timestamp(6) not null,
    updated_at timestamp(6) not null,
    constraint fk_agent_team_members_team foreign key (team_id) references agent_teams(id),
    constraint fk_agent_team_members_agent foreign key (agent_id) references agents(id)
);

create table agent_plans (
    id bigint primary key auto_increment,
    team_id bigint not null,
    goal varchar(128) not null,
    context_summary varchar(1024),
    status varchar(32) not null,
    created_at timestamp(6) not null,
    updated_at timestamp(6) not null,
    constraint fk_agent_plans_team foreign key (team_id) references agent_teams(id)
);

create table agent_plan_steps (
    id bigint primary key auto_increment,
    plan_id bigint not null,
    step_order int not null,
    title varchar(128) not null,
    required_capability varchar(128) not null,
    assigned_agent_id bigint,
    status varchar(32) not null,
    created_at timestamp(6) not null,
    updated_at timestamp(6) not null,
    constraint fk_agent_plan_steps_plan foreign key (plan_id) references agent_plans(id),
    constraint fk_agent_plan_steps_agent foreign key (assigned_agent_id) references agents(id)
);

insert into agent_teams (code, name, objective, coordination_mode, workspace_policy, status, created_at, updated_at)
values
('INCIDENT-WARROOM', '故障处置团队', '跨智能体协同定位、研判并输出事件处置报告', 'LEADER_WORKER', 'sandbox:readonly-prod;artifact-required', 'ACTIVE', current_timestamp(6), current_timestamp(6));

insert into agent_team_members (team_id, agent_id, role, responsibility, created_at, updated_at)
values
(1, 3, 'Lead Operator', '告警分诊与处置建议', current_timestamp(6), current_timestamp(6)),
(1, 1, 'Business Analyst', '评估经营影响和成本风险', current_timestamp(6), current_timestamp(6));

insert into agent_plans (team_id, goal, context_summary, status, created_at, updated_at)
values
(1, '处理线上 5xx 告警', '参考 AgentMesh 的团队协作与 AgentScope 的事件/权限模型，要求所有步骤进入安全审计。', 'READY', current_timestamp(6), current_timestamp(6));

insert into agent_plan_steps (plan_id, step_order, title, required_capability, assigned_agent_id, status, created_at, updated_at)
values
(1, 1, '收集告警上下文', 'ops.incident.triage', 3, 'ASSIGNED', current_timestamp(6), current_timestamp(6)),
(1, 2, '评估业务影响', 'finance.analysis', 1, 'ASSIGNED', current_timestamp(6), current_timestamp(6)),
(1, 3, '生成处置报告', 'ops.incident.triage', 3, 'PENDING', current_timestamp(6), current_timestamp(6));

create index idx_agent_team_members_team on agent_team_members(team_id);
create index idx_agent_plans_team on agent_plans(team_id);
create index idx_agent_plan_steps_plan_order on agent_plan_steps(plan_id, step_order);

