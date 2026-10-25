package com.eap.platform.safety;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {"spring.flyway.enabled=true", "eap.security.mode=JWT",
        "eap.security.issuer-uri=https://idp.invalid.example/realm/eap", "eap.security.jwk-set-uri=https://idp.invalid.example/jwks",
        "eap.workflow.execution.worker-enabled=false", "eap.evaluation.async-worker-enabled=false", "eap.substrate.deploy-worker-enabled=false"})
@ActiveProfiles("test") @AutoConfigureMockMvc @Transactional
class SafetyAssociationIntegrationTests {
    @Autowired MockMvc mvc; @Autowired ObjectMapper mapper; @Autowired JdbcTemplate jdbc;
    @Autowired GenseeSafetyService service; @Autowired EntityManager em;
    @Autowired SafetySessionRepository sessions; @Autowired SafetyEventRepository events;
    @Autowired SafetyArtifactRepository artifacts; @Autowired SafetyDecisionLogRepository decisions;
    Long eventId;
    @BeforeEach void setup() {
        service.createSession(new SafetyDtos.SessionRequest("association-A", null, "synthetic first"));
        service.createSession(new SafetyDtos.SessionRequest("association-B", null, "synthetic second"));
        eventId = service.evaluate(request("association-A")).event().id();
        em.flush();
    }
    SafetyDtos.SafetyEventRequest request(String key) {
        return new SafetyDtos.SafetyEventRequest(key, null, null, SafetyEventType.ARTIFACT_WRITE,
                "write", "artifact://synthetic/report", "synthetic summary", "{}");
    }
    String body(String key, Long event) throws Exception {
        return mapper.writeValueAsString(Map.of("sessionKey", key, "eventId", event,
                "artifactUri", "artifact://synthetic/report", "artifactType", "REPORT", "contentHash", "synthetic-hash"));
    }
    void reject(String key, Long event, String message) throws Exception {
        long artifactCount = artifacts.count(), eventCount = events.count(), decisionCount = decisions.count();
        mvc.perform(post("/api/safety/artifacts").contentType("application/json").content(body(key, event))
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_GOVERNANCE_ADMIN"))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value(message));
        em.flush(); em.clear();
        assertThat(artifacts.count()).isEqualTo(artifactCount);
        assertThat(events.count()).isEqualTo(eventCount);
        assertThat(decisions.count()).isEqualTo(decisionCount);
        assertThat(events.findById(eventId).orElseThrow().sessionKey).isEqualTo("association-A");
    }

    @Test void crossSessionArtifactIsRejectedWithoutChangingAnySafetyRecord() throws Exception {
        reject("association-B", eventId, "安全事件不属于指定会话");
    }
    @Test void nonexistentSessionIsRejected() throws Exception {
        reject("association-missing", eventId, "安全会话不存在");
    }
    @Test void nonexistentEventIsRejected() throws Exception {
        reject("association-A", Long.MAX_VALUE, "安全事件不存在");
    }
    @ParameterizedTest @CsvSource({"VIEWER,false", "OPERATOR,false", "GOVERNANCE_ADMIN,true", "PLATFORM_ADMIN,true"})
    void matchingAssociationPreservesPermissionAndPersistsInItsOwnSession(String role, boolean allowed) throws Exception {
        long before = artifacts.count();
        mvc.perform(post("/api/safety/artifacts").contentType("application/json").content(body("association-A", eventId))
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_" + role))))
                .andExpect(status().is(allowed ? 200 : 403));
        em.flush(); em.clear();
        assertThat(artifacts.count()).isEqualTo(before + (allowed ? 1 : 0));
        assertThat(service.listArtifacts("association-B")).isEmpty();
        if (allowed) {
            var saved = service.listArtifacts("association-A").getFirst();
            assertThat(saved.eventId()).isEqualTo(eventId);
            assertThat(saved.sessionKey()).isEqualTo("association-A");
            assertThat(saved.contentHash()).isEqualTo("synthetic-hash");
        }
    }
    @Test void exactSessionRegistrationStillUpdatesMetadataWithoutRenaming() {
        var before = sessions.findBySessionKey("association-A").orElseThrow();
        var updated = service.createSession(new SafetyDtos.SessionRequest("association-A", null, "updated objective"));
        em.flush(); em.clear();
        assertThat(updated.id()).isEqualTo(before.id);
        assertThat(sessions.findById(before.id).orElseThrow().objective).isEqualTo("updated objective");
    }
    @Test void metadataEvaluationRetainsPrivacyAndUsableArtifactAssociation() {
        var evaluated = service.evaluateMetadata(request("association-A"));
        var saved = service.registerArtifact(new SafetyDtos.ArtifactRequest("association-A", evaluated.event().id(),
                "artifact://synthetic/metadata", "REPORT", null));
        em.flush(); em.clear();
        var event = events.findById(saved.eventId()).orElseThrow();
        assertThat(event.sessionKey).isEqualTo("association-A");
        assertThat(event.payloadJson).isNull();
        assertThat(event.payloadSummary).isEqualTo("运行实例工具执行检查；参数不保存");
        assertThat(event.target).isEqualTo("tool://write");
    }
}
