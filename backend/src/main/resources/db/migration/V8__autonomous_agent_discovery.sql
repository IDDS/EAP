create table discovery_runs (
    id bigint primary key auto_increment,
    trigger_type varchar(32) not null,
    status varchar(32) not null,
    observations_count int not null,
    discovered_count int not null,
    error_message varchar(1024),
    started_at timestamp(6) not null,
    finished_at timestamp(6)
);

create table discovered_agents (
    id bigint primary key auto_increment,
    fingerprint varchar(64) not null,
    name varchar(256) not null,
    vendor varchar(128) not null,
    product varchar(128) not null,
    version varchar(64),
    asset_type varchar(32) not null,
    status varchar(32) not null,
    risk_level varchar(32) not null,
    confidence int not null,
    source_types varchar(256) not null,
    host_name varchar(256),
    account_name varchar(256),
    process_name varchar(256),
    executable_path varchar(1024),
    auto_approve boolean,
    trusted_process boolean,
    mcp_server_count int not null,
    evidence_count int not null,
    managed_agent_id bigint,
    first_seen_at timestamp(6) not null,
    last_seen_at timestamp(6) not null,
    created_at timestamp(6) not null,
    updated_at timestamp(6) not null,
    constraint uk_discovered_agents_fingerprint unique (fingerprint),
    constraint fk_discovered_agents_managed_agent foreign key (managed_agent_id) references agents(id)
);

create table discovery_observations (
    id bigint primary key auto_increment,
    run_id bigint not null,
    discovered_agent_id bigint not null,
    source_type varchar(32) not null,
    source_location varchar(1024) not null,
    evidence_type varchar(128) not null,
    evidence_json text,
    observed_at timestamp(6) not null,
    constraint fk_discovery_observations_run foreign key (run_id) references discovery_runs(id),
    constraint fk_discovery_observations_agent foreign key (discovered_agent_id) references discovered_agents(id)
);

create index idx_discovered_agents_status_risk on discovered_agents(status, risk_level);
create index idx_discovered_agents_last_seen on discovered_agents(last_seen_at);
create index idx_discovery_runs_started on discovery_runs(started_at);
create index idx_discovery_observations_agent on discovery_observations(discovered_agent_id, observed_at);
create index idx_discovery_observations_run on discovery_observations(run_id);
