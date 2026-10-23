create table eap_evaluation_dataset_items (
    id bigint not null auto_increment,
    dataset_id bigint not null,
    item_key varchar(128) not null,
    evaluator_key varchar(128) not null,
    evaluator_version varchar(64),
    agent_id bigint,
    trace_id varchar(128),
    span_id varchar(128),
    input_json text,
    expected_json text,
    actual_json text,
    ground_truth_label varchar(16),
    ground_truth_score decimal(8,6),
    annotation_rationale varchar(2048),
    annotated_by varchar(128),
    annotated_at timestamp(6),
    created_at timestamp(6) not null,
    updated_at timestamp(6) not null,
    primary key (id),
    unique key uk_eap_evaluation_dataset_items_key (dataset_id, item_key),
    constraint fk_eap_evaluation_dataset_items_dataset foreign key (dataset_id)
        references eap_evaluation_datasets(id),
    constraint fk_eap_evaluation_dataset_items_agent foreign key (agent_id)
        references agents(id)
);

create index idx_eap_evaluation_dataset_items_dataset_time
    on eap_evaluation_dataset_items(dataset_id, created_at);

create index idx_eap_evaluation_dataset_items_label
    on eap_evaluation_dataset_items(ground_truth_label, annotated_at);
