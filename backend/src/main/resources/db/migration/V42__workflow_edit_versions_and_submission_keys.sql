alter table workflow_nodes add column version bigint not null default 0;
alter table workflow_executions add column submission_key varchar(64);
alter table workflow_executions add column request_fingerprint varchar(64);
create unique index uk_workflow_execution_submission on workflow_executions(workflow_id, submission_key);
