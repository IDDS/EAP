package com.eap.platform.discovery;

import java.sql.DriverManager;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class ContentRetentionMigrationTests {
    @Test void migratingFrom86PreservesRawEvidenceAndInitializesOnlyNewCursors() throws Exception {
        String url = System.getProperty("spring.datasource.url", "jdbc:h2:mem:retention_migration_" +
                java.util.UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE");
        String user = System.getProperty("spring.datasource.username", "sa");
        String password = System.getProperty("spring.datasource.password", "");
        Flyway.configure().dataSource(url,user,password).locations("classpath:db/migration").target("86").load().migrate();
        try(var c=DriverManager.getConnection(url,user,password); var s=c.createStatement()) {
            s.executeUpdate("insert into business_content_records(observation_key,tenant_id,endpoint_key,direction,content_hash,content_bytes,encrypted_content,encryption_iv,encryption_key_version,raw_retained,sampled,observed_at,created_at) values('migration-fixture','test','test','INPUT','fingerprint',10,'synthetic ciphertext','synthetic iv','fixture-v1',true,true,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
            s.executeUpdate("update agentops_signal_sync_state set last_created_count=17");
        }
        Flyway.configure().dataSource(url,user,password).locations("classpath:db/migration").load().migrate();
        try(var c=DriverManager.getConnection(url,user,password); var s=c.createStatement()) {
            try(var r=s.executeQuery("select encrypted_content,raw_erased_at,raw_retained from business_content_records where observation_key='migration-fixture'")) {
                assertThat(r.next()).isTrue(); assertThat(r.getString(1)).isEqualTo("synthetic ciphertext");
                assertThat(r.getObject(2)).isNull(); assertThat(r.getBoolean(3)).isTrue();
            }
            try(var r=s.executeQuery("select last_created_count,approval_cursor,approval_upper_bound,evaluation_cursor,evaluation_upper_bound from agentops_signal_sync_state where id=1")) {
                assertThat(r.next()).isTrue(); assertThat(r.getInt(1)).isEqualTo(17);
                for(int i=2;i<=5;i++) assertThat(r.getLong(i)).isZero();
            }
        }
    }
}
