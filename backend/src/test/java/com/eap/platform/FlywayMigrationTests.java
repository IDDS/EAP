package com.eap.platform;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

import java.sql.DriverManager;

import static org.assertj.core.api.Assertions.assertThat;

class FlywayMigrationTests {
    @Test
    void appliesAllMigrationsAndSeedDataOnH2() throws Exception {
        String url = "jdbc:h2:mem:eap_migration_test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";
        Flyway flyway = Flyway.configure()
                .dataSource(url, "sa", "")
                .locations("classpath:db/migration")
                .load();

        var result = flyway.migrate();

        assertThat(result.migrationsExecuted).isEqualTo(30);
        try (var connection = DriverManager.getConnection(url, "sa", "");
             var statement = connection.createStatement()) {
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
                    "select count(*) from information_schema.tables where table_name = 'provider_usage_snapshots'"
            )) {
                tables.next();
                assertThat(tables.getLong(1)).isEqualTo(1);
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
            try (var columns = statement.executeQuery(
                    "select count(*) from information_schema.columns " +
                            "where table_name = 'agent_runtime_instances' and column_name = 'dispatch_authenticated'"
            )) {
                columns.next();
                assertThat(columns.getLong(1)).isEqualTo(1);
            }
        }
    }
}
