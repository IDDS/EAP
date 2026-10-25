package com.eap.platform.safety;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import com.eap.platform.common.BusinessException;
import static org.assertj.core.api.Assertions.*;

/** Explicit remote-only test against a disposable, loopback-bound MySQL database. */
class SafetyAssociationMySqlIT extends SafetyAssociationIntegrationTests {
    @DynamicPropertySource static void isolatedMySql(DynamicPropertyRegistry properties) {
        String url = System.getenv("EAP_SAFETY_MYSQL_URL");
        if (url == null || !url.startsWith("jdbc:mysql://127.0.0.1:3306/eap_safety_fixture?")) {
            throw new IllegalStateException("Disposable loopback MySQL required");
        }
        properties.add("spring.datasource.url", () -> url);
        properties.add("spring.datasource.driver-class-name", () -> "com.mysql.cj.jdbc.Driver");
        properties.add("spring.datasource.username", () -> "root");
        properties.add("spring.datasource.password", () -> "");
        properties.add("spring.jpa.database-platform", () -> "org.hibernate.dialect.MySQLDialect");
    }

    @ParameterizedTest @ValueSource(strings = {"resume", "RESUME", "résumé"})
    void realCaseAndAccentInsensitiveLookupCannotRewriteOrBindAnotherSpelling(String alias) {
        assertThat(jdbc.queryForObject("select @@collation_database", String.class)).isEqualTo("utf8mb4_0900_ai_ci");
        service.createSession(new SafetyDtos.SessionRequest("Résumé", null, "original objective"));
        em.flush(); em.clear();
        var original = sessions.findBySessionKey(alias).orElseThrow();
        assertThat(original.sessionKey).isEqualTo("Résumé"); // Proves a real collation alias, not a missing row.
        long eventCount = events.count(), decisionCount = decisions.count(), artifactCount = artifacts.count();
        assertThatThrownBy(() -> service.createSession(new SafetyDtos.SessionRequest(alias, null, "changed")))
                .isInstanceOf(BusinessException.class).hasMessageContaining("标识不匹配");
        assertThatThrownBy(() -> service.evaluate(request(alias))).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.evaluateMetadata(request(alias))).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.registerArtifact(new SafetyDtos.ArtifactRequest(alias, eventId,
                "artifact://synthetic/alias", "REPORT", null))).isInstanceOf(BusinessException.class);
        em.flush(); em.clear();
        assertThat(events.count()).isEqualTo(eventCount);
        assertThat(decisions.count()).isEqualTo(decisionCount);
        assertThat(artifacts.count()).isEqualTo(artifactCount);
        var unchanged = sessions.findById(original.id).orElseThrow();
        assertThat(unchanged.sessionKey).isEqualTo("Résumé");
        assertThat(unchanged.objective).isEqualTo("original objective");
    }
}
