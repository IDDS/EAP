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

        assertThat(result.migrationsExecuted).isEqualTo(21);
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
        }
    }
}
