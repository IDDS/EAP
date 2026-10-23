create table workflow_executions (
    id bigint primary key auto_increment,
    workflow_id bigint not null,
    run_key varchar(128) not null,
    status varchar(32) not null,
    objective varchar(128) not null,
    requester varchar(128) not null,
    snapshot_json text not null,
    steps_json mediumtext not null,
    reason varchar(512),
    created_at timestamp(6) not null,
    started_at timestamp(6),
    completed_at timestamp(6),
    constraint uk_workflow_execution_key unique(run_key),
    constraint fk_workflow_execution_definition foreign key(workflow_id) references workflow_definitions(id)
);
create index idx_workflow_execution_queue on workflow_executions(status, created_at, id);
create index idx_workflow_execution_history on workflow_executions(workflow_id, created_at, id);
