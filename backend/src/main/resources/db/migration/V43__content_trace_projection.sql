alter table business_content_records add column projection_status varchar(16) not null default 'PENDING';
alter table business_content_records add column projection_attempts integer not null default 0;
alter table business_content_records add column projection_error varchar(128);
create index idx_content_projection on business_content_records(direction, projection_status, id);

insert into eap_evaluators
    (evaluator_key, name, description, evaluator_type, level, implementation_key, version, threshold, enabled, created_at, updated_at)
values
    ('MODEL_CALL_STATUS', '模型调用状态（非任务完成度）',
     '根据采集回调或 HTTP 终态判断模型调用是否成功；不读取正文，不判断回答质量或业务任务是否完成。未知终态不自动评分。',
     'RULE', 'SPAN', 'MODEL_CALL_STATUS', 'v1', 0.500000, true, current_timestamp(6), current_timestamp(6));
