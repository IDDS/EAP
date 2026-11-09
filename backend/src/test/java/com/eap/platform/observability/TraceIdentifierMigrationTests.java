package com.eap.platform.observability;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import java.sql.DriverManager;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;

class TraceIdentifierMigrationTests {
    @Test void existingAndOldWriterRowsRemainUnknownAfterUpgrade() throws Exception {
        String url = "jdbc:h2:mem:trace_source_" + UUID.randomUUID() + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";
        var initial = Flyway.configure().dataSource(url, "sa", "").locations("classpath:db/migration").target("100").load().migrate();
        assertThat(initial.migrationsExecuted).isEqualTo(100);
        String insert = "insert into trace_span_observations(trace_id,span_id,endpoint_key,span_name,span_kind,duration_ms,observed_at,created_at) values('%s','span','fixture','fixture','AGENT',0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)";
        try (var connection = DriverManager.getConnection(url, "sa", ""); var statement = connection.createStatement()) {
            statement.executeUpdate(insert.formatted("before-upgrade"));
        }
        assertThat(Flyway.configure().dataSource(url, "sa", "").locations("classpath:db/migration").load().migrate().migrationsExecuted).isEqualTo(1);
        try (var connection = DriverManager.getConnection(url, "sa", ""); var statement = connection.createStatement()) {
            statement.executeUpdate(insert.formatted("old-writer"));
            try (var rows = statement.executeQuery("select trace_id_source,span_id_source from trace_span_observations where trace_id in ('before-upgrade','old-writer')")) {
                int count = 0;
                while (rows.next()) { count++; assertThat(rows.getString(1)).isEqualTo("UNKNOWN"); assertThat(rows.getString(2)).isEqualTo("UNKNOWN"); }
                assertThat(count).isEqualTo(2);
            }
        }
    }
}
