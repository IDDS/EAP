package com.eap.platform.deployment;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import java.sql.DriverManager;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;

class ChatSubmissionMigrationTests {
    @Test void upgradesV84WithoutChangingExistingAgentsAndKeepsReceiptOnRepeatMigration() throws Exception {
        String configured = System.getProperty("spring.datasource.url", "");
        String user = "sa", url = "jdbc:h2:mem:chat_upgrade_" + UUID.randomUUID() + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";
        if (configured.startsWith("jdbc:mysql:")) {
            // Only the isolated loopback test database is eligible, never an application database.
            assertThat(configured).startsWith("jdbc:mysql://127.0.0.1:3306/eap_isolation_fixture?");
            user = "root";
            try (var c = DriverManager.getConnection(configured, user, ""); var s = c.createStatement()) {
                s.executeUpdate("create database eap_chat_upgrade");
            }
            url = configured.replace("/eap_isolation_fixture?", "/eap_chat_upgrade?");
        }
        Flyway.configure().dataSource(url, user, "").locations("classpath:db/migration").target("84").load().migrate();
        long before;
        try (var c = DriverManager.getConnection(url, user, ""); var s = c.createStatement(); var r = s.executeQuery("select count(*) from agents")) {
            r.next(); before = r.getLong(1);
        }
        var flyway = Flyway.configure().dataSource(url, user, "").locations("classpath:db/migration").target("85").load();
        assertThat(flyway.migrate().migrationsExecuted).isEqualTo(1);
        try (var c = DriverManager.getConnection(url, user, ""); var s = c.createStatement()) {
            try (var r = s.executeQuery("select count(*) from agents")) { r.next(); assertThat(r.getLong(1)).isEqualTo(before); }
            s.executeUpdate("insert into eap_chat_submissions(owner_hash,request_key,agent_id,instance_name,payload_digest,status,created_at,dispatch_deadline,updated_at) values('synthetic-owner','00000000-0000-0000-0000-000000000001',7,'synthetic','synthetic-digest','UNKNOWN',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
        }
        assertThat(flyway.migrate().migrationsExecuted).isZero();
        try (var c = DriverManager.getConnection(url, user, ""); var s = c.createStatement(); var r = s.executeQuery("select status from eap_chat_submissions")) {
            r.next(); assertThat(r.getString(1)).isEqualTo("UNKNOWN"); assertThat(r.next()).isFalse();
        }
    }
}
