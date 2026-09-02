insert into eap_evaluators
    (evaluator_key, name, description, evaluator_type, level, implementation_key, version, threshold, enabled, created_at, updated_at)
values
    ('TRACE_ACTION_COMPLETION', 'Trace 任务完成度',
     '基于 Trace 中的模型/Agent 步骤、终态和显式任务完成信号，判断一次任务是否完成；未提供显式信号时使用保守启发式。',
     'RULE', 'TRACE', 'ACTION_COMPLETION', 'v1', 0.500000, true, current_timestamp(6), current_timestamp(6));
