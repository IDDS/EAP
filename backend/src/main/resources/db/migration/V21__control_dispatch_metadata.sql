alter table agent_control_commands add column runtime_instance_key varchar(128);
alter table agent_control_commands add column attempt_count int not null default 0;
alter table agent_control_commands add column http_status int;
alter table agent_control_commands add column acknowledged_at timestamp(6) null;
