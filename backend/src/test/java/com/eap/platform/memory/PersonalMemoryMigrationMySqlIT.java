package com.eap.platform.memory;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import java.sql.DriverManager;
import static org.assertj.core.api.Assertions.assertThat;

/** Only runs against a separately created, empty loopback test database. */
class PersonalMemoryMigrationMySqlIT {
    @Test void upgrades98WithoutChangingExistingValues() throws Exception {
        String url = System.getenv("EAP_MEMORY_MIGRATION_MYSQL_URL");
        if (url == null || !url.startsWith("jdbc:mysql://127.0.0.1:3306/eap_memory_upgrade?"))
            throw new IllegalStateException("Isolated loopback MySQL required");
        Flyway.configure().dataSource(url, "root", "").locations("classpath:db/migration").target("98").load().migrate();
        try (var c = DriverManager.getConnection(url, "root", ""); var q = c.createStatement()) {
            q.executeUpdate("insert into management_value_events(event_key,value_type,validated_by,benefit_money,benefit_provenance,currency,observed_at,created_at) values('memory-upgrade-fixture','OTHER','fixture',10,'REPORTED','USD',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
        }
        assertThat(Flyway.configure().dataSource(url, "root", "").locations("classpath:db/migration").load().migrate().migrationsExecuted).isEqualTo(1);
        try (var c = DriverManager.getConnection(url, "root", ""); var q = c.createStatement()) {
            try (var r = q.executeQuery("select benefit_money,benefit_provenance from management_value_events where event_key='memory-upgrade-fixture'")) {
                assertThat(r.next()).isTrue();
                assertThat(r.getBigDecimal(1)).isEqualByComparingTo("10");
                assertThat(r.getString(2)).isEqualTo("REPORTED");
            }
            try (var r = q.executeQuery("select count(*) from personal_memories")) {
                assertThat(r.next()).isTrue();
                assertThat(r.getLong(1)).isZero();
            }
        }
    }
}
