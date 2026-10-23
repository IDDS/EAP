alter table model_invocation_events
    add column usage_pool_key varchar(128);

create index idx_model_invocation_usage_pool_observed
    on model_invocation_events(usage_pool_key, observed_at);

create table provider_usage_snapshots (
    id bigint auto_increment primary key,
    snapshot_key varchar(128) not null unique,
    usage_pool_key varchar(128) not null,
    provider varchar(128) not null,
    account_label varchar(128),
    window_start timestamp(6) not null,
    window_end timestamp(6) not null,
    input_tokens bigint not null,
    output_tokens bigint not null,
    cached_input_tokens bigint not null,
    reasoning_tokens bigint not null,
    money_cost decimal(18, 6) not null,
    currency varchar(16) not null,
    source varchar(32) not null,
    observed_at timestamp(6) not null,
    created_at timestamp(6) not null
);

create index idx_provider_usage_pool_window
    on provider_usage_snapshots(usage_pool_key, window_start, window_end);

create index idx_provider_usage_observed
    on provider_usage_snapshots(observed_at);
