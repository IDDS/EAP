alter table eap_evaluation_results
    add column evaluator_version varchar(64) not null default 'v1';

alter table eap_evaluation_results
    add column request_fingerprint varchar(64);

update eap_evaluation_results result
set evaluator_version = (
    select evaluator.version
    from eap_evaluators evaluator
    where evaluator.id = result.evaluator_id
);
