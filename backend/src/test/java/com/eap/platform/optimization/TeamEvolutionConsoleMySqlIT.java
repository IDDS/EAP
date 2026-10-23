package com.eap.platform.optimization;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Explicit remote-only isolated MySQL contract run; no production Secret or endpoint. */
class TeamEvolutionConsoleMySqlIT extends TeamEvolutionIntegrationTests {
    @DynamicPropertySource
    static void isolatedMySql(DynamicPropertyRegistry properties) {
        String url=System.getenv("EAP_ISOLATED_MYSQL_URL");
        if(url==null || !url.startsWith("jdbc:mysql://127.0.0.1:3306/eap_holdout_fixture?")) throw new IllegalStateException("Isolated loopback MySQL fixture required");
        properties.add("spring.datasource.url",()->url);
        properties.add("spring.datasource.driver-class-name",()->"com.mysql.cj.jdbc.Driver");
        properties.add("spring.datasource.username",()->"root"); properties.add("spring.datasource.password",()->"");
        properties.add("spring.jpa.hibernate.ddl-auto",()->"none"); properties.add("spring.jpa.database-platform",()->"org.hibernate.dialect.MySQLDialect");
    }
}
