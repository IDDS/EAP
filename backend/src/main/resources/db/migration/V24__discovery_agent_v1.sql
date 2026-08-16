alter table discovery_endpoints add column installation_id varchar(64);
alter table discovery_endpoints add column machine_fingerprint varchar(128);
alter table discovery_endpoints add column credential_hash varchar(64);
alter table discovery_endpoints add column credential_hint varchar(32);
alter table discovery_endpoints add column content_credential_hash varchar(64);
alter table discovery_endpoints add column content_credential_hint varchar(32);
alter table discovery_endpoints add column tenant_id varchar(64) not null default 'default';
alter table discovery_endpoints add column site_name varchar(128);
alter table discovery_endpoints add column environment_name varchar(64);
alter table discovery_endpoints add column department varchar(128);
alter table discovery_endpoints add column labels_json text;
alter table discovery_endpoints add column architecture varchar(32);
alter table discovery_endpoints add column capabilities_json text;
alter table discovery_endpoints add column policy_version varchar(64);
alter table discovery_endpoints add column rules_version varchar(64);
alter table discovery_endpoints add column last_heartbeat_at timestamp(6);
alter table discovery_endpoints add column last_scan_at timestamp(6);
alter table discovery_endpoints add column queue_depth bigint not null default 0;
alter table discovery_endpoints add column queue_bytes bigint not null default 0;
alter table discovery_endpoints add column last_error varchar(1024);
alter table discovery_endpoints add column fingerprint_review_required boolean not null default false;
alter table discovery_endpoints add column content_capture_status varchar(32) not null default 'DISABLED';
alter table discovery_endpoints add column content_platform_approved_by varchar(128);
alter table discovery_endpoints add column content_platform_approved_at timestamp(6);
alter table discovery_endpoints add column content_data_approved_by varchar(128);
alter table discovery_endpoints add column content_data_approved_at timestamp(6);
alter table discovery_endpoints add column uninstalled_at timestamp(6);

create unique index uk_discovery_endpoint_installation on discovery_endpoints(installation_id);
create unique index uk_discovery_endpoint_credential on discovery_endpoints(credential_hash);
create unique index uk_discovery_endpoint_content_credential on discovery_endpoints(content_credential_hash);
create index idx_discovery_endpoint_tenant_status on discovery_endpoints(tenant_id, status);

alter table discovered_agents add column availability_status varchar(32) not null default 'ONLINE';
alter table discovered_agents add column last_endpoint_key varchar(128);
alter table discovered_agents add column missing_snapshot_count int not null default 0;

create index idx_discovered_agents_endpoint_availability
    on discovered_agents(last_endpoint_key, availability_status);

create table discovery_enrollment_tokens (
    id bigint auto_increment primary key,
    token_hash varchar(64) not null unique,
    token_hint varchar(32) not null,
    tenant_id varchar(64) not null,
    site_name varchar(128),
    environment_name varchar(64),
    department varchar(128),
    labels_json text,
    max_uses int not null,
    used_count int not null,
    status varchar(32) not null,
    expires_at timestamp(6) not null,
    created_by varchar(128) not null,
    created_at timestamp(6) not null,
    last_used_at timestamp(6)
);

create index idx_discovery_enrollment_expiry on discovery_enrollment_tokens(status, expires_at);

create table discovery_ingestion_batches (
    id bigint auto_increment primary key,
    endpoint_key varchar(128) not null,
    batch_id varchar(128) not null,
    scan_id varchar(128),
    snapshot_type varchar(32) not null,
    observations_count int not null,
    discovered_count int not null,
    status varchar(32) not null,
    received_at timestamp(6) not null,
    completed_at timestamp(6),
    constraint uk_discovery_batch unique (endpoint_key, batch_id)
);

create index idx_discovery_batch_endpoint_time on discovery_ingestion_batches(endpoint_key, received_at);

create table business_content_records (
    id bigint auto_increment primary key,
    observation_key varchar(128) not null unique,
    tenant_id varchar(64) not null,
    endpoint_key varchar(128) not null,
    agent_id bigint,
    task_id bigint,
    trace_id varchar(128),
    span_id varchar(128),
    direction varchar(32) not null,
    provider varchar(128),
    model_name varchar(128),
    content_hash varchar(64) not null,
    content_bytes bigint not null,
    encrypted_content longtext,
    encryption_iv varchar(64),
    encryption_key_version varchar(32),
    redacted_content longtext,
    metadata_json text,
    sensitivity varchar(32),
    data_category varchar(128),
    raw_retained boolean not null,
    sampled boolean not null,
    expires_at timestamp(6),
    observed_at timestamp(6) not null,
    created_at timestamp(6) not null
);

create index idx_business_content_endpoint_time on business_content_records(endpoint_key, observed_at);
create index idx_business_content_agent_time on business_content_records(agent_id, observed_at);
create index idx_business_content_expiry on business_content_records(expires_at);
