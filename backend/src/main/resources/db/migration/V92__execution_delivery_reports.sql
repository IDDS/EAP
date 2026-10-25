create table execution_delivery_reports (
    id bigint not null auto_increment primary key,
    stream_key varchar(64) not null,
    agent_id bigint not null,
    runtime_instance_key varchar(128) not null,
    identity_generation varchar(36) not null,
    producer_id varchar(36) not null,
    framework varchar(64) not null,
    framework_version varchar(32) not null,
    report_sequence bigint not null,
    report_digest varchar(64) not null,
    total_events int not null,
    acknowledged_events int not null,
    pending_events int not null,
    delivery_attempts bigint not null,
    delivery_state varchar(32) not null,
    received_at timestamp(6) not null,
    constraint uk_execution_delivery_stream unique (stream_key),
    constraint uk_execution_delivery_producer unique (runtime_instance_key, producer_id)
);
create index idx_execution_delivery_agent on execution_delivery_reports (agent_id, id);
