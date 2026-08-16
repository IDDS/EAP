alter table management_value_events add column metric_name varchar(128);
alter table management_value_events add column metric_unit varchar(32);
alter table management_value_events add column baseline_value decimal(18, 6);
alter table management_value_events add column current_value decimal(18, 6);
alter table management_value_events add column improvement_direction varchar(32);

create index idx_management_value_metric_observed
    on management_value_events(value_type, observed_at);
