package com.eap.platform.orchestration;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import java.sql.DriverManager;
import static org.assertj.core.api.Assertions.assertThat;

class PlanContractMigrationMySqlIT {
    @Test void upgrades88WithoutChangingOriginalPlanOrSteps() throws Exception {
        String source=System.getenv("EAP_ISOLATED_MYSQL_URL");
        if(source==null||!source.startsWith("jdbc:mysql://127.0.0.1:3306/eap_holdout_fixture?"))throw new IllegalStateException("Isolated MySQL required");
        String url=source.replace("/eap_holdout_fixture?","/eap_plan_upgrade_fixture?");
        Flyway.configure().dataSource(url,"root","").locations("classpath:db/migration").target("88").load().migrate();
        long count;
        try(var c=DriverManager.getConnection(url,"root","");var q=c.createStatement()) {
            q.executeUpdate("update agent_plans set status='EXECUTION_UNKNOWN',execution_run_key='original-plan-unknown' where id=1");
            q.executeUpdate("update agent_plan_steps set status='EXECUTION_UNKNOWN' where plan_id=1");
            try(var r=q.executeQuery("select count(*) from agent_plan_steps where plan_id=1")){r.next();count=r.getLong(1);assertThat(count).isGreaterThan(0);}
        }
        var result=Flyway.configure().dataSource(url,"root","").locations("classpath:db/migration").load().migrate();
        assertThat(result.migrationsExecuted).isEqualTo(1);
        try(var c=DriverManager.getConnection(url,"root","");var q=c.createStatement()) {
            try(var r=q.executeQuery("select status,execution_run_key from agent_plans where id=1")){assertThat(r.next()).isTrue();assertThat(r.getString(1)).isEqualTo("EXECUTION_UNKNOWN");assertThat(r.getString(2)).isEqualTo("original-plan-unknown");}
            try(var r=q.executeQuery("select count(*) from agent_plan_steps where plan_id=1 and status='EXECUTION_UNKNOWN' and output_contract_json is null")){r.next();assertThat(r.getLong(1)).isEqualTo(count);}
        }
    }
}
