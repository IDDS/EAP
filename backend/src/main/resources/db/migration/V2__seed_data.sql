insert into agents (code, name, description, owner_department, owner_user, category, lifecycle_status, risk_level, created_at, updated_at)
values
('FIN-ANALYST', '财务分析智能体', '辅助财务部门进行经营数据分析和异常解释', '财务部', '张敏', '经营分析', 'ACTIVE', 'MEDIUM', current_timestamp(6), current_timestamp(6)),
('HR-ASSISTANT', '人力资源助手', '辅助员工查询制度、假期和入职流程', '人力资源部', '李晨', '员工服务', 'REVIEWING', 'LOW', current_timestamp(6), current_timestamp(6)),
('OPS-INCIDENT', '运维事件智能体', '辅助识别告警原因并生成处置建议', '信息技术部', '王磊', '运维管理', 'PAUSED', 'HIGH', current_timestamp(6), current_timestamp(6));

insert into governance_policies (name, policy_type, scope_type, scope_value, rule_json, enforcement_mode, enabled, created_at, updated_at)
values
('高风险工具调用审批', 'SECURITY', 'GLOBAL', null, '{"toolRiskLevel":["HIGH","CRITICAL"],"approvalRequired":true}', 'APPROVAL_REQUIRED', true, current_timestamp(6), current_timestamp(6)),
('个人敏感信息输出拦截', 'PRIVACY', 'GLOBAL', null, '{"piiTypes":["ID_CARD","PHONE","BANK_CARD"],"action":"MASK_OR_BLOCK"}', 'BLOCK', true, current_timestamp(6), current_timestamp(6));

