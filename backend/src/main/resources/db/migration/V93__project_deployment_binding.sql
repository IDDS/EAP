create table platform_project_binding (
    id integer not null primary key,
    project_key varchar(48),
    bound_at timestamp(6),
    constraint chk_platform_project_singleton check (id = 1)
);
insert into platform_project_binding (id, project_key, bound_at) values (1, null, null);
