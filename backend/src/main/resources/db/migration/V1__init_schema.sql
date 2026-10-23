create table agents (
    id bigint primary key auto_increment,
    code varchar(64) not null,
    name varchar(128) not null,
    description varchar(1024),
    owner_department varchar(128) not null,
    owner_user varchar(128) not null,
    category varchar(64) not null,
    lifecycle_status varchar(32) not null,
    risk_level varchar(32) not null,
    created_at timestamp(6) not null,
    updated_at timestamp(6) not null,
    constraint uk_agents_code unique (code)
);

create table agent_versions (
    id bigint primary key auto_increment,
    agent_id bigint not null,
    version_no varchar(32) not null,
    model_provider varchar(64) not null,
    model_name varchar(128) not null,
    system_prompt text,
    reasoning_policy json,
    tool_policy json,
    knowledge_policy json,
    release_status varchar(32) not null,
    published_at timestamp(6) null,
    created_at timestamp(6) not null,
    updated_at timestamp(6) not null,
    constraint fk_agent_versions_agent foreign key (agent_id) references agents(id),
    constraint uk_agent_versions unique (agent_id, version_no)
);

create table tools (
    id bigint primary key auto_increment,
    code varchar(64) not null,
    name varchar(128) not null,
    endpoint varchar(512) not null,
    auth_type varchar(32) not null,
    timeout_ms int not null,
    risk_level varchar(32) not null,
    enabled boolean not null,
    created_at timestamp(6) not null,
    updated_at timestamp(6) not null,
    constraint uk_tools_code unique (code)
);

create table agent_tool_bindings (
    id bigint primary key auto_increment,
    agent_id bigint not null,
    tool_id bigint not null,
    approval_status varchar(32) not null,
    created_at timestamp(6) not null,
    updated_at timestamp(6) not null,
    constraint fk_agent_tool_bindings_agent foreign key (agent_id) references agents(id),
    constraint fk_agent_tool_bindings_tool foreign key (tool_id) references tools(id),
    constraint uk_agent_tool_bindings unique (agent_id, tool_id)
);

create table agent_tasks (
    id bigint primary key auto_increment,
    agent_id bigint not null,
    agent_version_id bigint null,
    task_type varchar(64) not null,
    input_summary varchar(1024),
    status varchar(32) not null,
    started_at timestamp(6) null,
    finished_at timestamp(6) null,
    latency_ms bigint null,
    token_cost bigint null,
    money_cost decimal(18, 6) null,
    result_summary varchar(1024),
    created_at timestamp(6) not null,
    updated_at timestamp(6) not null,
    constraint fk_agent_tasks_agent foreign key (agent_id) references agents(id),
    constraint fk_agent_tasks_version foreign key (agent_version_id) references agent_versions(id)
);

create table governance_policies (
    id bigint primary key auto_increment,
    name varchar(128) not null,
    policy_type varchar(32) not null,
    scope_type varchar(32) not null,
    scope_value varchar(128),
    rule_json json not null,
    enforcement_mode varchar(32) not null,
    enabled boolean not null,
    created_at timestamp(6) not null,
    updated_at timestamp(6) not null
);

create table audit_logs (
    id bigint primary key auto_increment,
    actor varchar(128) not null,
    action varchar(128) not null,
    resource_type varchar(64) not null,
    resource_id varchar(64),
    before_json json,
    after_json json,
    result varchar(32) not null,
    trace_id varchar(64) not null,
    created_at timestamp(6) not null
);

create table users (
    id bigint primary key auto_increment,
    username varchar(64) not null,
    display_name varchar(128) not null,
    department varchar(128),
    enabled boolean not null,
    created_at timestamp(6) not null,
    updated_at timestamp(6) not null,
    constraint uk_users_username unique (username)
);

create table roles (
    id bigint primary key auto_increment,
    code varchar(64) not null,
    name varchar(128) not null,
    created_at timestamp(6) not null,
    updated_at timestamp(6) not null,
    constraint uk_roles_code unique (code)
);

create table user_roles (
    id bigint primary key auto_increment,
    user_id bigint not null,
    role_id bigint not null,
    created_at timestamp(6) not null,
    constraint fk_user_roles_user foreign key (user_id) references users(id),
    constraint fk_user_roles_role foreign key (role_id) references roles(id),
    constraint uk_user_roles unique (user_id, role_id)
);

create index idx_agents_status_risk on agents(lifecycle_status, risk_level);
create index idx_agent_tasks_agent_status on agent_tasks(agent_id, status);
create index idx_governance_policies_type_enabled on governance_policies(policy_type, enabled);
create index idx_audit_logs_resource on audit_logs(resource_type, resource_id);

