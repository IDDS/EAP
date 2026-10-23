package com.eap.platform.registry;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import java.sql.DriverManager;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;

class RuntimeWorkMigrationTests {
    @Test void upgradesV85AndRetainsLegacyUnknownWithoutReplayingOrExpiringIt() throws Exception {
        String configured = System.getProperty("spring.datasource.url", "");
        String user = "sa", url = "jdbc:h2:mem:reclaim_upgrade_" + UUID.randomUUID() + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";
        if (configured.startsWith("jdbc:mysql:")) {
            assertThat(configured).startsWith("jdbc:mysql://127.0.0.1:3306/eap_isolation_fixture?");
            user = "root";
            try (var c = DriverManager.getConnection(configured, user, ""); var s = c.createStatement()) { s.executeUpdate("create database eap_reclaim_upgrade"); }
            url = configured.replace("/eap_isolation_fixture?", "/eap_reclaim_upgrade?");
        }
        Flyway.configure().dataSource(url, user, "").locations("classpath:db/migration").target("85").load().migrate();
        try (var c = DriverManager.getConnection(url, user, ""); var s = c.createStatement()) {
            s.executeUpdate("insert into agent_runtime_instances(agent_id,instance_key,endpoint,protocol,status,load_score,created_at,updated_at) values (1,'legacy/actor','substrate://legacy/actor','SUBSTRATE','ONLINE',0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
            s.executeUpdate("update agent_runtime_instances set platform_actor_uid='synthetic-bound-uid',platform_actor_template='legacy/template' where instance_key='legacy/actor'");
            s.executeUpdate("insert into workflow_executions(workflow_id,run_key,status,objective,requester,snapshot_json,steps_json,created_at) values (1,'legacy-unknown','EXECUTION_UNKNOWN','synthetic','fixture','{}','[{\"runtimeInstanceKey\":\"legacy/actor\",\"status\":\"EXECUTION_UNKNOWN\"}]',CURRENT_TIMESTAMP)");
            s.executeUpdate("insert into workflow_executions(workflow_id,run_key,status,objective,requester,snapshot_json,steps_json,created_at) values (1,'legacy-unparseable','EXECUTION_UNKNOWN','synthetic','fixture','{}','original legacy steps',CURRENT_TIMESTAMP)");
            // This mutable alias may have pointed at a different actor when the old task was sent.
            s.executeUpdate("insert into agent_runtime_instances(agent_id,instance_key,endpoint,protocol,status,load_score,created_at,updated_at) values (1,'mutable-alias','substrate://changed/replacement','SUBSTRATE','ONLINE',0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
            s.executeUpdate("insert into workflow_executions(workflow_id,run_key,status,objective,requester,snapshot_json,steps_json,created_at) values (1,'legacy-mutable','EXECUTION_UNKNOWN','synthetic','fixture','{}','[{\"runtimeInstanceKey\":\"mutable-alias\",\"status\":\"EXECUTION_UNKNOWN\"}]',CURRENT_TIMESTAMP)");
            s.executeUpdate("insert into eap_chat_submissions(owner_hash,request_key,agent_id,instance_name,payload_digest,status,created_at,dispatch_deadline,updated_at) values('synthetic-owner','00000000-0000-0000-0000-000000000001',1,'missing-actor','synthetic-digest','UNKNOWN',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
        }
        var flyway = Flyway.configure().dataSource(url, user, "").locations("classpath:db/migration").target("86").load();
        assertThat(flyway.migrate().migrationsExecuted).isEqualTo(1);
        assertThat(flyway.migrate().migrationsExecuted).isZero();
        try (var c = DriverManager.getConnection(url, user, ""); var s = c.createStatement()) {
            try (var r = s.executeQuery("select actor_key,state from runtime_work_claims order by actor_key")) {
                assertThat(r.next()).isTrue(); assertThat(r.getString(1)).isEqualTo("*"); assertThat(r.getString(2)).isEqualTo("UNKNOWN");
                assertThat(r.next()).isTrue(); assertThat(r.getString(1)).isEqualTo("legacy/actor"); assertThat(r.getString(2)).isEqualTo("UNKNOWN");
                assertThat(r.next()).isFalse();
            }
            try (var r = s.executeQuery("select status from workflow_executions where run_key='legacy-unknown'")) { r.next(); assertThat(r.getString(1)).isEqualTo("EXECUTION_UNKNOWN"); }
            try (var r = s.executeQuery("select count(*) from runtime_work_actors")) { r.next(); assertThat(r.getLong(1)).isZero(); }
        }
    }
}
