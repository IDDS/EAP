alter table learning_feedbacks add column task_id bigint;
alter table learning_feedbacks add column trace_id varchar(128);
alter table learning_feedbacks add column requested_by varchar(128);
alter table learning_feedbacks add column reviewed_by varchar(128);
alter table learning_feedbacks add column submission_key varchar(64);
alter table learning_feedbacks add column request_fingerprint varchar(64);
alter table learning_feedbacks add constraint fk_feedback_task foreign key (task_id) references agent_tasks(id);
create unique index uq_feedback_submission on learning_feedbacks(agent_id, submission_key);
