create table discovery_identity_links (
    id bigint auto_increment primary key,
    identity_key varchar(64) not null,
    discovered_agent_id bigint not null,
    relation_type varchar(32) not null,
    confidence int not null,
    identity_basis varchar(1024),
    source_types varchar(256) not null,
    first_seen_at timestamp(6) not null,
    last_seen_at timestamp(6) not null,
    created_at timestamp(6) not null,
    updated_at timestamp(6) not null,
    constraint uk_discovery_identity_key unique (identity_key),
    constraint fk_discovery_identity_agent foreign key (discovered_agent_id) references discovered_agents(id)
);

create index idx_discovery_identity_agent on discovery_identity_links(discovered_agent_id);
create index idx_discovery_identity_last_seen on discovery_identity_links(last_seen_at);
