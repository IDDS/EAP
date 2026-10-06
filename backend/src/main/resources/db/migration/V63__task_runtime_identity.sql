-- Existing tasks retain their original shared-credential execution contract.
alter table ax_tasks add column identity_mode varchar(24) not null default 'LEGACY_SHARED';
alter table ax_tasks add column identity_scope varchar(63);
alter table ax_tasks add column runner_image varchar(768);
alter table ax_tasks add column identity_public_key varchar(256);
alter table ax_tasks add column identity_gateway_url varchar(2048);
alter table ax_tasks add column identity_gateway_host varchar(253);
alter table ax_tasks add column delivery_request varchar(36);
alter table ax_tasks add column instance_key varchar(128);
alter table ax_tasks add column actor_uid varchar(64);
alter table ax_tasks add column actor_template varchar(128);
alter table ax_tasks add column identity_until timestamp(6);
alter table ax_tasks add column identity_revoked boolean not null default false;
create unique index uk_ax_task_runtime_instance on ax_tasks(instance_key);
