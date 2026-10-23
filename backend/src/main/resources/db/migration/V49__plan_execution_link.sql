alter table agent_plans add column execution_workflow_id bigint;
alter table agent_plans add column execution_run_key varchar(128);
alter table agent_plans add column execution_request_key varchar(64);
alter table agent_plans add column execution_fingerprint varchar(64);
alter table agent_plans add constraint uk_plan_execution_workflow unique (execution_workflow_id);
alter table agent_plans add constraint uk_plan_execution_run unique (execution_run_key);
alter table agent_plans add constraint fk_plan_execution_workflow foreign key (execution_workflow_id) references workflow_definitions(id);
create index idx_agent_plans_execution_status on agent_plans(status, execution_workflow_id);
