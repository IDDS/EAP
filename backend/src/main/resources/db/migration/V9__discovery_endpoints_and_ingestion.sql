create table discovery_endpoints (
    id bigint auto_increment primary key,
    endpoint_key varchar(128) not null unique,
    host_name varchar(256) not null,
    account_name varchar(256),
    operating_system varchar(128),
    collector_version varchar(64),
    status varchar(32) not null,
    first_seen_at timestamp(6) not null,
    last_seen_at timestamp(6) not null,
    created_at timestamp(6) not null,
    updated_at timestamp(6) not null
);

alter table discovery_runs add column endpoint_key varchar(128);

create index idx_discovery_endpoints_last_seen on discovery_endpoints(last_seen_at);
create index idx_discovery_runs_endpoint on discovery_runs(endpoint_key, started_at);
