package com.eap.platform.optimization;

import com.eap.platform.registry.RuntimeConnectionRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.util.ReflectionTestUtils;
import java.sql.Timestamp;
import java.time.Instant;
import static org.assertj.core.api.Assertions.*;

/** Reproduces the production +08 JDBC session against JPA's UTC Instant mapping. */
class TeamEvolutionTimezoneMySqlIT extends TeamEvolutionExecutionIntegrationTests {
    @Autowired RuntimeConnectionRepository connectionRows;
    @DynamicPropertySource
    static void isolatedMySql(DynamicPropertyRegistry properties) {
        String url=System.getenv("EAP_ISOLATED_MYSQL_URL");
        if(url==null||!url.startsWith("jdbc:mysql://127.0.0.1:3306/eap_holdout_fixture?")
                ||!url.contains("connectionTimeZone=Asia/Shanghai")||!url.contains("forceConnectionTimeZoneToSession=true"))
            throw new IllegalStateException("Isolated loopback +08 MySQL fixture required");
        properties.add("spring.datasource.url",()->url);
        properties.add("spring.datasource.driver-class-name",()->"com.mysql.cj.jdbc.Driver");
        properties.add("spring.datasource.username",()->"root");properties.add("spring.datasource.password",()->"");
        properties.add("spring.jpa.hibernate.ddl-auto",()->"none");
        properties.add("spring.jpa.database-platform",()->"org.hibernate.dialect.MySQLDialect");
    }
    @Test void jpaIdentityExpiryRemainsAuthoritativeInNonUtcJdbcSession() {
        Instant until=Instant.now().plusSeconds(600);
        for(String key:java.util.List.of(firstInstance,secondInstance)) {
            var row=connectionRows.findByInstanceKey(key).orElseThrow();
            ReflectionTestUtils.setField(row,"expiresAt",until);
            ReflectionTestUtils.setField(row,"identityExpiresAt",until.plusSeconds(3600));
            connectionRows.saveAndFlush(row);
        }
        // Prove the real mismatch rather than testing only handwritten future timestamps.
        assertThat(db.queryForObject("select TIMESTAMPDIFF(SECOND,UTC_TIMESTAMP(),CURRENT_TIMESTAMP())",Long.class)).isEqualTo(28800L);
        Timestamp plain=db.queryForObject("select expires_at from runtime_connections where instance_key=?",Timestamp.class,firstInstance);
        assertThat(plain.toInstant()).isBefore(Instant.now());
        var original=connectionRows.findByInstanceKey(firstInstance).orElseThrow();
        assertThat((Instant)ReflectionTestUtils.getField(original,"expiresAt")).isAfter(Instant.now());
        var f=completed("jpa-utc-identity");
        assertThat(score(f).denominator()).isEqualTo(1);
        assertThat(scoring.frontier(f.id()).costs().settledCalls()).isEqualTo(2);
    }
}
