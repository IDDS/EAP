package com.eap.platform.registry;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

class AgentExecutionObservationMySqlIT extends AgentExecutionObservationIntegrationTests {
    @Override protected String migrationJdbcUrl() {
        String url=System.getenv("EAP_ISOLATED_MYSQL_URL");
        if(url==null||!url.startsWith("jdbc:mysql://127.0.0.1:3306/eap_holdout_fixture?"))throw new IllegalStateException("Isolated loopback MySQL required");
        return url.replace("/eap_holdout_fixture?","/eap_execution_upgrade_"+java.util.UUID.randomUUID().toString().replace("-","")+"?");
    }
    @DynamicPropertySource static void isolatedMySql(DynamicPropertyRegistry p) {
        String url=System.getenv("EAP_ISOLATED_MYSQL_URL");
        if(url==null||!url.startsWith("jdbc:mysql://127.0.0.1:3306/eap_holdout_fixture?"))throw new IllegalStateException("Isolated loopback MySQL required");
        p.add("spring.datasource.url",()->url);p.add("spring.datasource.driver-class-name",()->"com.mysql.cj.jdbc.Driver");
        p.add("spring.datasource.username",()->"root");p.add("spring.datasource.password",()->"");
        p.add("spring.jpa.hibernate.ddl-auto",()->"none");p.add("spring.jpa.database-platform",()->"org.hibernate.dialect.MySQLDialect");
    }
}
