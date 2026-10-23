alter table governance_preflight_decisions
    add column approval_status varchar(32) not null default 'NOT_REQUIRED';

alter table governance_preflight_decisions
    add column approved_by varchar(128);

alter table governance_preflight_decisions
    add column approval_reason varchar(512);

alter table governance_preflight_decisions
    add column approved_at timestamp(6);

alter table governance_preflight_decisions
    add column request_fingerprint varchar(64) not null default '';

create index idx_governance_preflight_approval on governance_preflight_decisions(approval_status, decided_at);
