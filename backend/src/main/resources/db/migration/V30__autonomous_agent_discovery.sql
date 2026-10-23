alter table agent_runtime_instances
    add column dispatch_authenticated boolean not null default false;
