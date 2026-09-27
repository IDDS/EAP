-- Console deployments of agents onto Agent Substrate (docs/SUBSTRATE_RUNTIME_INTEGRATION.md section 6).
-- Each row is one immutable template version; config_json never contains provider credentials.
create table substrate_agent_deployments (
    id bigint primary key auto_increment,
    deployment_key varchar(64) not null,
    agent_id bigint not null,
    version int not null,
    source_type varchar(16) not null,
    status varchar(32) not null,
    config_json text not null,
    image_ref varchar(512),
    atespace varchar(63) not null,
    template_name varchar(63) not null,
    instance_key varchar(128),
    steps_json text not null,
    reason varchar(512),
    requested_by varchar(128) not null,
    submission_key varchar(64),
    created_at timestamp(6) not null,
    updated_at timestamp(6) not null,
    constraint uk_substrate_deployment_key unique(deployment_key),
    constraint uk_substrate_deployment_version unique(agent_id, version),
    constraint uk_substrate_deployment_submission unique(agent_id, submission_key)
);
create index idx_substrate_deployment_queue on substrate_agent_deployments(status, updated_at, id);
