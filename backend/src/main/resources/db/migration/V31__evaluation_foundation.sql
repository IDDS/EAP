create table eap_evaluators (
    id bigint not null auto_increment,
    evaluator_key varchar(128) not null,
    name varchar(256) not null,
    description varchar(2048),
    evaluator_type varchar(32) not null,
    level varchar(32) not null,
    implementation_key varchar(128) not null,
    version varchar(64) not null,
    threshold decimal(8,6) not null default 0.500000,
    enabled boolean not null default true,
    created_at timestamp(6) not null,
    updated_at timestamp(6) not null,
    primary key (id),
    unique key uk_eap_evaluators_key (evaluator_key)
);

create table eap_evaluation_datasets (
    id bigint not null auto_increment,
    dataset_key varchar(128) not null,
    name varchar(256) not null,
    description varchar(2048),
    source_type varchar(32) not null,
    version varchar(64) not null,
    item_count int not null default 0,
    created_at timestamp(6) not null,
    updated_at timestamp(6) not null,
    primary key (id),
    unique key uk_eap_evaluation_datasets_key (dataset_key)
);

create table eap_evaluation_results (
    id bigint not null auto_increment,
    result_key varchar(128) not null,
    evaluator_id bigint not null,
    dataset_id bigint,
    agent_id bigint,
    trace_id varchar(128),
    span_id varchar(128),
    level varchar(32) not null,
    status varchar(32) not null,
    score decimal(8,6) not null,
    passed boolean not null,
    confidence decimal(8,6) not null,
    rationale varchar(2048),
    input_json text,
    expected_json text,
    actual_json text,
    evidence_json text,
    evaluated_at timestamp(6) not null,
    created_at timestamp(6) not null,
    primary key (id),
    unique key uk_eap_evaluation_results_key (result_key),
    constraint fk_eap_evaluation_results_evaluator foreign key (evaluator_id) references eap_evaluators(id),
    constraint fk_eap_evaluation_results_dataset foreign key (dataset_id) references eap_evaluation_datasets(id),
    constraint fk_eap_evaluation_results_agent foreign key (agent_id) references agents(id)
);

create index idx_eap_evaluation_results_evaluator_time
    on eap_evaluation_results(evaluator_id, evaluated_at);
create index idx_eap_evaluation_results_agent_time
    on eap_evaluation_results(agent_id, evaluated_at);
create index idx_eap_evaluation_results_trace_time
    on eap_evaluation_results(trace_id, evaluated_at);

insert into eap_evaluators
    (evaluator_key, name, description, evaluator_type, level, implementation_key, version, threshold, enabled, created_at, updated_at)
values
    ('ACTION_COMPLETION', '任务完成度', '判断 Agent 是否完成用户目标。', 'RULE', 'SESSION', 'ACTION_COMPLETION', 'v1', 0.500000, true, current_timestamp(6), current_timestamp(6)),
    ('TOOL_ARGUMENT_VALIDITY', '工具参数合法性', '检查工具必填参数是否完整。', 'RULE', 'SPAN', 'TOOL_ARGUMENT_VALIDITY', 'v1', 1.000000, true, current_timestamp(6), current_timestamp(6)),
    ('TOOL_STATUS', '工具调用结果', '检查工具调用是否成功完成。', 'RULE', 'SPAN', 'TOOL_STATUS', 'v1', 1.000000, true, current_timestamp(6), current_timestamp(6)),
    ('PII_POLICY', '个人敏感信息策略', '检查输出或工具结果是否包含不应继续流转的个人敏感信息。', 'RULE', 'SPAN', 'PII_POLICY', 'v1', 1.000000, true, current_timestamp(6), current_timestamp(6)),
    ('SENSITIVE_APPROVAL', '敏感操作审批', '检查敏感操作是否已获得审批。', 'RULE', 'SPAN', 'SENSITIVE_APPROVAL', 'v1', 1.000000, true, current_timestamp(6), current_timestamp(6)),
    ('COST_BUDGET', '成本预算', '检查一次任务或调用的成本是否在预算内。', 'RULE', 'TRACE', 'COST_BUDGET', 'v1', 0.500000, true, current_timestamp(6), current_timestamp(6));
