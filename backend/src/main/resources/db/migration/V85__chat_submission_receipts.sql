-- Durable dispatch tombstones. No prompts, history, replies, or credentials are stored here.
-- Do not expire/delete keys: a delayed request must never become a new model dispatch.
create table eap_chat_submissions (
    owner_hash varchar(64) not null,
    request_key varchar(36) not null,
    agent_id bigint not null,
    instance_name varchar(128) not null,
    payload_digest varchar(64) not null,
    status varchar(24) not null,
    trace_id varchar(64),
    result_digest varchar(64),
    turns integer,
    created_at timestamp(6) not null,
    dispatch_deadline timestamp(6) not null,
    updated_at timestamp(6) not null,
    primary key (owner_hash, request_key)
);
