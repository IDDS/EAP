create table learning_feedbacks (
    id bigint primary key auto_increment,
    agent_id bigint not null,
    feedback_type varchar(64) not null,
    rating int not null,
    summary varchar(1024) not null,
    improvement_action varchar(1024),
    status varchar(32) not null,
    created_at timestamp(6) not null,
    updated_at timestamp(6) not null,
    constraint fk_learning_feedbacks_agent foreign key (agent_id) references agents(id)
);

create table safety_sessions (
    id bigint primary key auto_increment,
    session_key varchar(128) not null,
    root_agent_id bigint,
    objective varchar(128) not null,
    status varchar(32) not null,
    created_at timestamp(6) not null,
    updated_at timestamp(6) not null,
    constraint uk_safety_sessions_key unique (session_key),
    constraint fk_safety_sessions_root_agent foreign key (root_agent_id) references agents(id)
);

create table safety_events (
    id bigint primary key auto_increment,
    session_key varchar(128) not null,
    agent_id bigint,
    parent_agent_id bigint,
    event_type varchar(32) not null,
    action varchar(128) not null,
    target varchar(256) not null,
    payload_summary varchar(1024),
    payload_json json,
    created_at timestamp(6) not null
);

create table safety_decision_logs (
    id bigint primary key auto_increment,
    event_id bigint not null,
    session_key varchar(128) not null,
    decision varchar(16) not null,
    severity varchar(16) not null,
    reason_code varchar(64) not null,
    reason varchar(1024) not null,
    control_actions varchar(1024) not null,
    sidecar_mode varchar(32) not null,
    created_at timestamp(6) not null,
    constraint fk_safety_decision_logs_event foreign key (event_id) references safety_events(id)
);

create table safety_artifacts (
    id bigint primary key auto_increment,
    session_key varchar(128) not null,
    event_id bigint not null,
    artifact_uri varchar(512) not null,
    artifact_type varchar(64) not null,
    content_hash varchar(128),
    created_at timestamp(6) not null,
    constraint fk_safety_artifacts_event foreign key (event_id) references safety_events(id)
);

insert into agent_tasks (agent_id, agent_version_id, task_type, input_summary, status, started_at, finished_at, latency_ms, token_cost, money_cost, result_summary, created_at, updated_at)
values
(1, null, 'FINANCE_ANALYSIS', '分析本月收入波动并解释异常科目', 'SUCCEEDED', current_timestamp(6), current_timestamp(6), 1860, 4200, 0.126000, '发现华东区渠道返点导致费用率升高', current_timestamp(6), current_timestamp(6)),
(3, null, 'INCIDENT_TRIAGE', '分析 Kubernetes 服务 5xx 告警', 'RUNNING', current_timestamp(6), null, null, null, null, null, current_timestamp(6), current_timestamp(6)),
(2, null, 'HR_POLICY_QA', '查询年假结转规则', 'FAILED', current_timestamp(6), current_timestamp(6), 920, 820, 0.018000, '知识库命中率不足，需要补充制度版本', current_timestamp(6), current_timestamp(6));

insert into learning_feedbacks (agent_id, feedback_type, rating, summary, improvement_action, status, created_at, updated_at)
values
(1, 'QUALITY', 4, '财务分析结论正确，但缺少成本影响量化。', '在提示词中要求输出金额影响和置信度。', 'REVIEWING', current_timestamp(6), current_timestamp(6)),
(2, 'KNOWLEDGE_GAP', 2, '未能识别新版假期制度。', '同步 2026 版员工手册并重新评估。', 'OPEN', current_timestamp(6), current_timestamp(6));

insert into safety_sessions (session_key, root_agent_id, objective, status, created_at, updated_at)
values
('demo-incident-001', 3, '多智能体协同处理线上 5xx 告警', 'ACTIVE', current_timestamp(6), current_timestamp(6));

insert into safety_events (session_key, agent_id, parent_agent_id, event_type, action, target, payload_summary, payload_json, created_at)
values
('demo-incident-001', 3, null, 'TOOL_CALL', 'kubectl describe pod', 'prod/kubernetes/default/api-pod', '运维智能体尝试访问生产集群 Pod 详情。', '{"tool":"kubectl","namespace":"default"}', current_timestamp(6)),
('demo-incident-001', 3, null, 'DELEGATION', 'delegate root cause analysis', 'ops-diagnosis-agent', '委托诊断智能体继续分析，但缺少父子链路确认。', '{"delegateTo":"ops-diagnosis-agent"}', current_timestamp(6)),
('demo-incident-001', 3, 3, 'ARTIFACT_WRITE', 'write report', 'artifact://incident/demo-incident-001/report.md', '生成事件分析报告。', '{"format":"markdown"}', current_timestamp(6));

insert into safety_decision_logs (event_id, session_key, decision, severity, reason_code, reason, control_actions, sidecar_mode, created_at)
values
(1, 'demo-incident-001', 'DENY', 'CRITICAL', 'SENSITIVE_OR_PRIVILEGED_ACCESS', '命中生产环境特权访问规则，按 fail-closed 拦截。', 'BLOCK;REQUIRE_SECURITY_REVIEW;WRITE_AUDIT', 'EAP_LOCAL_POLICY', current_timestamp(6)),
(2, 'demo-incident-001', 'ASK', 'MEDIUM', 'UNTRACED_DELEGATION', '多智能体委托缺少完整父子链路，需要确认。', 'REQUIRE_PARENT_AGENT;WRITE_AUDIT', 'EAP_LOCAL_POLICY', current_timestamp(6)),
(3, 'demo-incident-001', 'ALLOW', 'LOW', 'POLICY_PASS', '制品目标已登记，允许写入。', 'ALLOW;WRITE_AUDIT', 'EAP_LOCAL_POLICY', current_timestamp(6));

insert into safety_artifacts (session_key, event_id, artifact_uri, artifact_type, content_hash, created_at)
values
('demo-incident-001', 3, 'artifact://incident/demo-incident-001/report.md', 'INCIDENT_REPORT', 'sha256:demo', current_timestamp(6));

create index idx_learning_feedbacks_agent_status on learning_feedbacks(agent_id, status);
create index idx_safety_events_session_created on safety_events(session_key, created_at);
create index idx_safety_decisions_session_created on safety_decision_logs(session_key, created_at);
create index idx_safety_decisions_decision on safety_decision_logs(decision, severity);
create index idx_safety_artifacts_session on safety_artifacts(session_key, created_at);

