alter table agent_work_items
    add column row_version bigint not null default 0;
