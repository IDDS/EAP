alter table management_value_events add column source_type varchar(32) not null default 'MANUAL';
alter table management_value_events add column source_system varchar(128);
alter table management_value_events add column source_record_key varchar(128);
alter table management_value_events add column confidence decimal(5, 4) not null default 1.0000;
alter table management_value_events add column measurement_window_start timestamp(6);
alter table management_value_events add column measurement_window_end timestamp(6);

create unique index uq_management_value_source_record
    on management_value_events(source_system, source_record_key);

create table management_value_attributions (
    id bigint auto_increment primary key,
    value_event_id bigint not null,
    agent_id bigint not null,
    contribution_weight decimal(9, 6) not null,
    attribution_method varchar(32) not null,
    evidence_reference varchar(256),
    created_at timestamp(6) not null,
    constraint fk_management_attribution_event foreign key (value_event_id) references management_value_events(id),
    constraint fk_management_attribution_agent foreign key (agent_id) references agents(id),
    constraint uq_management_attribution_event_agent unique (value_event_id, agent_id)
);

create index idx_management_attribution_agent
    on management_value_attributions(agent_id, value_event_id);
