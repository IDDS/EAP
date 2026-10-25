create table agent_execution_events (
    id bigint not null auto_increment primary key,
    stream_key varchar(64) not null,
    event_id varchar(36) not null,
    producer_sequence bigint not null,
    event_digest varchar(64) not null,
    slot_key varchar(64) not null,
    agent_id bigint not null,
    runtime_instance_key varchar(128) not null,
    producer_id varchar(36) not null,
    event_kind varchar(32) not null,
    run_id varchar(36) not null,
    payload_json text not null,
    received_at timestamp(6) not null,
    constraint uk_execution_event unique (stream_key, event_id),
    constraint uk_execution_sequence unique (stream_key, producer_sequence),
    constraint uk_execution_slot unique (stream_key, slot_key)
);
create index idx_execution_agent on agent_execution_events (agent_id, id);
create index idx_execution_stream on agent_execution_events (stream_key, id);
create index idx_execution_producer on agent_execution_events (runtime_instance_key, producer_id);
