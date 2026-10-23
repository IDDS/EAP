create table agent_control_commands (
    id bigint primary key auto_increment,
    command_key varchar(128) not null,
    agent_id bigint not null,
    command_type varchar(64) not null,
    payload_json json,
    status varchar(32) not null,
    issued_by varchar(128) not null,
    dispatch_mode varchar(64) not null,
    result_message varchar(512),
    created_at timestamp(6) not null,
    updated_at timestamp(6) not null,
    dispatched_at timestamp(6) null,
    constraint uk_agent_control_commands_key unique (command_key),
    constraint fk_agent_control_commands_agent foreign key (agent_id) references agents(id)
);

create index idx_agent_control_commands_agent_status on agent_control_commands(agent_id, status);
