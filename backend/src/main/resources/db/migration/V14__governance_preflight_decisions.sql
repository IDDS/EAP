create table governance_preflight_decisions (
    id bigint auto_increment primary key,
    decision_key varchar(128) not null unique,
    data_event_id bigint not null unique,
    action varchar(32) not null,
    gateway_directive varchar(32) not null,
    request_allowed boolean not null,
    matched_policy_count int not null,
    matched_policies_json text,
    reason_codes_json text,
    classifier varchar(128) not null,
    provider varchar(128),
    model_name varchar(128),
    decided_at timestamp(6) not null,
    constraint fk_governance_preflight_event foreign key (data_event_id) references governance_data_events(id)
);

create index idx_governance_preflight_decided on governance_preflight_decisions(decided_at);
create index idx_governance_preflight_directive on governance_preflight_decisions(gateway_directive, decided_at);
