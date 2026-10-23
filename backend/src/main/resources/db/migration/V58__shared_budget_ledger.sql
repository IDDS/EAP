create table budget_admission_lock(id bigint primary key);
insert into budget_admission_lock(id) values(1);
create table budget_accounts (
 id bigint auto_increment primary key, scope_type varchar(16) not null, scope_key varchar(128) not null,
 unit varchar(24) not null, limit_amount bigint, margin_amount bigint not null default 0,
 used_amount decimal(30,0) not null default 0, reserved_amount decimal(30,0) not null default 0,
 revision bigint not null default 0, violated boolean not null default false,
 updated_by varchar(128), updated_at timestamp(6) not null,
 constraint uq_budget_scope unique(scope_type,scope_key,unit)
);
create table budget_reservations (
 id bigint auto_increment primary key, invocation_key varchar(64) not null unique, fingerprint varchar(64) not null,
 agent_id bigint, task_key varchar(128), experiment_id bigint, trace_id varchar(128),
 operation varchar(24) not null, output_bound bigint not null, state varchar(24) not null,
 fence varchar(36) not null, input_tokens bigint, output_tokens bigint, reason_code varchar(64),
 created_at timestamp(6) not null, settled_at timestamp(6)
);
create table budget_reservation_items (
 reservation_id bigint not null, account_id bigint not null, reserved_amount bigint not null,
 primary key(reservation_id,account_id),
 constraint fk_budget_item_reservation foreign key(reservation_id) references budget_reservations(id),
 constraint fk_budget_item_account foreign key(account_id) references budget_accounts(id)
);
create table budget_settlements (
 id bigint auto_increment primary key, reservation_id bigint not null unique, state varchar(24) not null,
 input_tokens bigint, output_tokens bigint, created_at timestamp(6) not null,
 constraint fk_budget_settlement_reservation foreign key(reservation_id) references budget_reservations(id)
);
create table budget_limit_changes (
 id bigint auto_increment primary key, account_id bigint not null, revision bigint not null,
 limit_amount bigint, margin_amount bigint not null, changed_by varchar(128) not null, created_at timestamp(6) not null,
 constraint fk_budget_change_account foreign key(account_id) references budget_accounts(id)
);
create index idx_budget_reservation_state on budget_reservations(state,created_at);
create index idx_budget_reservation_agent on budget_reservations(agent_id,id);
insert into budget_accounts(scope_type,scope_key,unit,updated_at) values('PLATFORM','default','MODEL_CALLS',CURRENT_TIMESTAMP);
insert into budget_accounts(scope_type,scope_key,unit,updated_at) values('PLATFORM','default','OUTPUT_TOKENS',CURRENT_TIMESTAMP);
insert into budget_accounts(scope_type,scope_key,unit,updated_at) values('PLATFORM','default','TOOL_CLAIMS',CURRENT_TIMESTAMP);
