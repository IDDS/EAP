alter table agent_tasks add column requester varchar(128);
alter table agent_tasks add column problem_resolved boolean null;
alter table agent_tasks add column resolution_summary varchar(1024);

create index idx_agent_tasks_requester_created on agent_tasks(requester, created_at);
create index idx_agent_tasks_resolution_created on agent_tasks(problem_resolved, created_at);
