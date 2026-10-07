package com.eap.platform;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

import java.sql.DriverManager;

import static org.assertj.core.api.Assertions.assertThat;

class FlywayMigrationTests {
    @Test
    void newRecoveryMigrationPreservesLegacyUnknownIntentWithoutEnrollment() throws Exception {
        String url="jdbc:h2:mem:eap_legacy_"+java.util.UUID.randomUUID()+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";
        Flyway.configure().dataSource(url,"sa","").locations("classpath:db/migration").target("63").load().migrate();
        try(var c=DriverManager.getConnection(url,"sa","");var st=c.createStatement()) {
            st.executeUpdate("insert into workflow_executions(workflow_id,run_key,status,objective,requester,snapshot_json,steps_json,created_at) values(1,'old-unknown','EXECUTION_UNKNOWN','old','owner','original-snapshot','original-steps',CURRENT_TIMESTAMP)");
        }
        Flyway.configure().dataSource(url,"sa","").locations("classpath:db/migration").load().migrate();
        try(var c=DriverManager.getConnection(url,"sa","");var st=c.createStatement();var row=st.executeQuery("select status,snapshot_json,steps_json,recovery_version,owner_epoch,owner_id from workflow_executions where run_key='old-unknown'")) {
            assertThat(row.next()).isTrue();assertThat(row.getString(1)).isEqualTo("EXECUTION_UNKNOWN");
            assertThat(row.getString(2)).isEqualTo("original-snapshot");assertThat(row.getString(3)).isEqualTo("original-steps");
            assertThat(row.getInt(4)).isZero();assertThat(row.getLong(5)).isZero();assertThat(row.getString(6)).isNull();
        }
    }
    @Test
    void version64OriginalQueryIsNotSilentlyEnrolledInEffectFence()throws Exception {
        String url="jdbc:h2:mem:old_query_"+java.util.UUID.randomUUID()+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";
        Flyway.configure().dataSource(url,"sa","").locations("classpath:db/migration").target("64").load().migrate();
        try(var c=DriverManager.getConnection(url,"sa","");var st=c.createStatement()) {
            st.executeUpdate("insert into workflow_executions(workflow_id,run_key,status,objective,requester,snapshot_json,steps_json,created_at,recovery_version,owner_epoch) values(1,'old-v64','EXECUTION_UNKNOWN','old','owner','original-snapshot','original-steps',CURRENT_TIMESTAMP,1,2)");
            st.executeUpdate("insert into workflow_tool_recovery(execution_id,sequence_number,node_key,task_key,root_id,operation_id,binding_json,request_json,state,reason,next_check_ms,created_ms) select id,0,'counter','original-task','original-root','original-O','original-binding','original-parameters','CONFIRMED','original-reason',0,0 from workflow_executions where run_key='old-v64'");
        }
        Flyway.configure().dataSource(url,"sa","").locations("classpath:db/migration").load().migrate();
        try(var c=DriverManager.getConnection(url,"sa","");var st=c.createStatement()) {
            try(var row=st.executeQuery("select count(*) from workflow_tool_effect_roots")){row.next();assertThat(row.getLong(1)).isZero();}
            try(var row=st.executeQuery("select state,binding_json,request_json,reason from workflow_tool_recovery")) {
                assertThat(row.next()).isTrue();assertThat(row.getString(1)).isEqualTo("CONFIRMED");assertThat(row.getString(2)).isEqualTo("original-binding");
                assertThat(row.getString(3)).isEqualTo("original-parameters");assertThat(row.getString(4)).isEqualTo("original-reason");assertThat(row.next()).isFalse();
            }
        }
    }
    @Test
    void fairSchedulingMigrationDoesNotBackfillHistoricalExecutions()throws Exception {
        String url="jdbc:h2:mem:old_schedule_"+java.util.UUID.randomUUID()+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";
        Flyway.configure().dataSource(url,"sa","").locations("classpath:db/migration").target("65").load().migrate();
        try(var c=DriverManager.getConnection(url,"sa","");var st=c.createStatement()) {
            st.executeUpdate("insert into workflow_executions(workflow_id,run_key,status,objective,requester,snapshot_json,steps_json,created_at,recovery_version,owner_epoch) values(1,'old-queue','QUEUED','old','original-owner','original-snapshot','original-steps',CURRENT_TIMESTAMP,1,2)");
        }
        Flyway.configure().dataSource(url,"sa","").locations("classpath:db/migration").load().migrate();
        try(var c=DriverManager.getConnection(url,"sa","");var st=c.createStatement()) {
            for(String table:java.util.List.of("workflow_schedule_entries","workflow_schedule_groups","workflow_phase_observations"))try(var r=st.executeQuery("select count(*) from "+table)){r.next();assertThat(r.getLong(1)).isZero();}
            try(var r=st.executeQuery("select status,snapshot_json,steps_json,owner_epoch from workflow_executions where run_key='old-queue'")){assertThat(r.next()).isTrue();assertThat(r.getString(1)).isEqualTo("QUEUED");assertThat(r.getString(2)).isEqualTo("original-snapshot");assertThat(r.getString(3)).isEqualTo("original-steps");assertThat(r.getLong(4)).isEqualTo(2);}
        }
    }
    @Test
    void appliesAllMigrationsAndSeedDataOnH2() throws Exception {
        String url = "jdbc:h2:mem:eap_migration_test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";
        Flyway flyway = Flyway.configure()
                .dataSource(url, "sa", "")
                .locations("classpath:db/migration")
                .load();

        var result = flyway.migrate();

        assertThat(result.migrationsExecuted).isEqualTo(66);
        try (var connection = DriverManager.getConnection(url, "sa", "");
             var statement = connection.createStatement()) {
            try (var tables=statement.executeQuery("select count(*) from information_schema.tables where table_name in ('scoped_tool_lock','scoped_tool_grants','scoped_tool_counters','scoped_tool_operations')")) {
                tables.next();assertThat(tables.getLong(1)).isEqualTo(4);
            }
            try(var columns=statement.executeQuery("select count(*) from information_schema.columns where table_name='runtime_tool_calls' and column_name in ('evidence_source','effect_id','root_grant_id')")) {
                columns.next();assertThat(columns.getLong(1)).isEqualTo(3);
            }

            try (var tables = statement.executeQuery("select count(*) from information_schema.tables where table_name in "
                    + "('workflow_executions', 'eap_evaluation_calibration_runs', 'agentops_signal_sync_state')")) {
                tables.next();
                assertThat(tables.getLong(1)).isEqualTo(3);
            }
            try (var columns = statement.executeQuery("select count(*) from information_schema.columns where table_name = 'trace_span_observations' and column_name in ('started_at', 'ended_at') and is_nullable = 'YES'")) {
                columns.next();
                assertThat(columns.getLong(1)).isEqualTo(2);
            }
            try (var rows = statement.executeQuery("select count(*) from agents")) {
                rows.next();
                assertThat(rows.getLong(1)).isEqualTo(3);
            }
            try (var columns = statement.executeQuery(
                    "select count(*) from information_schema.columns " +
                            "where table_name = 'governance_preflight_decisions' and column_name = 'approval_status'"
            )) {
                columns.next();
                assertThat(columns.getLong(1)).isEqualTo(1);
            }
            try (var columns = statement.executeQuery(
                    "select count(*) from information_schema.columns " +
                            "where table_name = 'agent_control_commands' and column_name = 'runtime_instance_key'"
            )) {
                columns.next();
                assertThat(columns.getLong(1)).isEqualTo(1);
            }
            try (var rows = statement.executeQuery(
                    "select count(*) from agents where record_origin = 'DEMO'"
            )) {
                rows.next();
                assertThat(rows.getLong(1)).isEqualTo(3);
            }
            try (var columns = statement.executeQuery(
                    "select count(*) from information_schema.columns " +
                            "where table_name = 'trace_span_observations' and column_name = 'agent_id'"
            )) {
                columns.next();
                assertThat(columns.getLong(1)).isEqualTo(1);
            }
            try (var tables = statement.executeQuery(
                    "select count(*) from information_schema.tables where table_name = 'tool_invocation_events'"
            )) {
                tables.next();
                assertThat(tables.getLong(1)).isEqualTo(1);
            }
            try (var tables = statement.executeQuery(
                    "select count(*) from information_schema.tables where table_name in ('provider_usage_snapshots', 'substrate_agent_deployments')"
            )) {
                tables.next();
                assertThat(tables.getLong(1)).isEqualTo(2);
            }
            try (var columns = statement.executeQuery(
                    "select count(*) from information_schema.columns " +
                            "where table_name = 'model_invocation_events' and column_name = 'usage_pool_key'"
            )) {
                columns.next();
                assertThat(columns.getLong(1)).isEqualTo(1);
            }
            try (var columns = statement.executeQuery(
                    "select count(*) from information_schema.columns " +
                            "where table_name = 'agent_tasks' and column_name in ('requester', 'problem_resolved', 'resolution_summary')"
            )) {
                columns.next();
                assertThat(columns.getLong(1)).isEqualTo(3);
            }
            try (var columns = statement.executeQuery(
                    "select count(*) from information_schema.columns " +
                            "where table_name = 'management_value_connectors' and column_name = 'task_id_path'"
            )) {
                columns.next();
                assertThat(columns.getLong(1)).isEqualTo(1);
            }
            try (var columns = statement.executeQuery(
                    "select count(*) from information_schema.columns " +
                            "where table_name = 'management_value_events' and column_name = 'baseline_value'"
            )) {
                columns.next();
                assertThat(columns.getLong(1)).isEqualTo(1);
            }
            try (var tables = statement.executeQuery(
                    "select count(*) from information_schema.tables where table_name = 'management_value_attributions'"
            )) {
                tables.next();
                assertThat(tables.getLong(1)).isEqualTo(1);
            }
            try (var tables = statement.executeQuery(
                    "select count(*) from information_schema.tables where table_name = 'management_value_connectors'"
            )) {
                tables.next();
                assertThat(tables.getLong(1)).isEqualTo(1);
            }
            try (var tables = statement.executeQuery(
                    "select count(*) from information_schema.tables where table_name = 'eap_evaluation_dataset_items'"
            )) {
                tables.next();
                assertThat(tables.getLong(1)).isEqualTo(1);
            }
            try (var columns = statement.executeQuery(
                    "select count(*) from information_schema.columns " +
                            "where table_name = 'agent_runtime_instances' and column_name = 'dispatch_authenticated'"
            )) {
                columns.next();
                assertThat(columns.getLong(1)).isEqualTo(1);
            }
            try (var tables = statement.executeQuery(
                    "select count(*) from information_schema.tables where table_name = 'eap_evaluators'"
            )) {
                tables.next();
                assertThat(tables.getLong(1)).isEqualTo(1);
            }
            try (var rows = statement.executeQuery("select count(*) from eap_evaluators")) {
                rows.next();
                assertThat(rows.getLong(1)).isEqualTo(9);
            }
            try (var tables = statement.executeQuery(
                    "select count(*) from information_schema.tables where table_name = 'eap_evaluation_jobs'"
            )) {
                tables.next();
                assertThat(tables.getLong(1)).isEqualTo(1);
            }
            try (var columns = statement.executeQuery(
                    "select count(*) from information_schema.columns " +
                            "where table_name = 'eap_evaluation_results' and column_name in ('evaluator_version', 'request_fingerprint')"
            )) {
                columns.next();
                assertThat(columns.getLong(1)).isEqualTo(2);
            }
        }
    }
}
