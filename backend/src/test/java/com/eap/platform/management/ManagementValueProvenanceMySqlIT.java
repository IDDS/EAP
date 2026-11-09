package com.eap.platform.management;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import java.sql.DriverManager;
import static org.assertj.core.api.Assertions.assertThat;

/** Only runs against an empty, loopback-only database created for this test. */
class ManagementValueProvenanceMySqlIT {
    @Test void upgrades97WithoutInventingLegacyAmountProvenance() throws Exception {
        String url = System.getenv("EAP_VALUE_MYSQL_URL");
        if (url == null || !url.startsWith("jdbc:mysql://127.0.0.1:3306/eap_it?"))
            throw new IllegalStateException("Isolated loopback MySQL required");
        Flyway.configure().dataSource(url, "root", "").locations("classpath:db/migration").target("97").load().migrate();
        try (var c = DriverManager.getConnection(url, "root", ""); var q = c.createStatement()) {
            q.executeUpdate("insert into management_value_events(event_key,value_type,validated_by,benefit_money,currency,observed_at,created_at) values('legacy-zero','OTHER','fixture',0,'USD',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP),('legacy-positive','OTHER','fixture',10,'USD',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
        }
        assertThat(Flyway.configure().dataSource(url, "root", "").locations("classpath:db/migration").target("98").load().migrate().migrationsExecuted).isEqualTo(1);
        try (var c = DriverManager.getConnection(url, "root", ""); var q = c.createStatement(); var r = q.executeQuery("select benefit_money,benefit_provenance from management_value_events where event_key in ('legacy-zero','legacy-positive') order by event_key")) {
            assertThat(r.next()).isTrue();
            assertThat(r.getBigDecimal(1)).isEqualByComparingTo("10");
            assertThat(r.getString(2)).isNull();
            assertThat(r.next()).isTrue();
            assertThat(r.getBigDecimal(1)).isEqualByComparingTo("0");
            assertThat(r.getString(2)).isNull();
            assertThat(r.next()).isFalse();
        }
    }
}
