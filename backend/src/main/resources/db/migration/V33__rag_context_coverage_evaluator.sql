insert into eap_evaluators
    (evaluator_key, name, description, evaluator_type, level, implementation_key, version, threshold, enabled, created_at, updated_at)
values
    ('RAG_CONTEXT_COVERAGE', 'RAG 上下文覆盖度',
     '基于检索源数量、输出引用、引用重合、采用结果和人工质量分，判断 RAG 上下文是否形成可追踪证据；不等同于语义相关性或事实正确性。',
     'RULE', 'TRACE', 'RAG_CONTEXT_COVERAGE', 'v1', 0.500000, true, current_timestamp(6), current_timestamp(6));
