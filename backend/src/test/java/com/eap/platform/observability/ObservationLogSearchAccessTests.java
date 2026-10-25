package com.eap.platform.observability;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"spring.flyway.enabled=true","eap.security.mode=JWT","eap.security.issuer-uri=https://idp.invalid.example/realm/eap","eap.security.jwk-set-uri=https://idp.invalid.example/jwks","eap.substrate.deploy-worker-enabled=false"})
@ActiveProfiles("test") @AutoConfigureMockMvc @Transactional
class ObservationLogSearchAccessTests {
    @Autowired MockMvc mvc; @Autowired JdbcTemplate db;
    @ParameterizedTest @ValueSource(strings={"VIEWER","OPERATOR","GOVERNANCE_ADMIN","PLATFORM_ADMIN"})
    void consoleCapabilitiesReadButAllMutationMethodsAreDenied(String role)throws Exception {
        var auth=jwt().authorities(new SimpleGrantedAuthority("ROLE_"+role));
        long before=db.queryForObject("select count(*) from observation_operation_events",Long.class);
        mvc.perform(get("/api/observability/logs").with(auth)).andExpect(status().isOk()).andExpect(jsonPath("$.data.sourceContract").value("PERSISTED_OPERATION_EVENT_TEXT_V1"));
        mvc.perform(get("/api/platform/me").with(auth)).andExpect(status().isOk()).andExpect(jsonPath("$.data.capabilities.viewConsole").value(true));
        mvc.perform(post("/api/observability/logs").with(auth)).andExpect(status().isForbidden());
        mvc.perform(delete("/api/observability/logs").with(auth)).andExpect(status().isForbidden());
        mvc.perform(get("/api/observability/logs/hidden").with(auth)).andExpect(status().isForbidden());
        assertThat(db.queryForObject("select count(*) from observation_operation_events",Long.class)).isEqualTo(before);
    }
    @Test void anonymousAndRolesWithoutConsoleCapabilityCannotSearch()throws Exception {
        mvc.perform(get("/api/observability/logs")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/observability/logs").with(jwt().authorities(new SimpleGrantedAuthority("ROLE_DATA_OWNER")))).andExpect(status().isForbidden());
    }
    @Test void jwtProductionSearchNeverReturnsDemoAgentEventsOrCountsThemAsCandidates()throws Exception {
        Long demo=db.queryForObject("select min(id) from agents where record_origin='DEMO'",Long.class);assertThat(demo).isNotNull();
        db.update("insert into observation_operations(entry_kind,operation_key,agent_id,registration_state,expected_agent_spans,expected_model_events,created_at) values('CHAT','PUBLIC_DEMO_LOG',?,'PENDING_IDENTITY',1,1,CURRENT_TIMESTAMP)",demo);
        Long id=db.queryForObject("select id from observation_operations where operation_key='PUBLIC_DEMO_LOG'",Long.class);
        db.update("insert into observation_operation_events values(?,'REGISTERED','ORIGINAL_INTENT_FROZEN',NULL,CURRENT_TIMESTAMP)",id);
        mvc.perform(get("/api/observability/logs?query=registered").with(jwt().authorities(new SimpleGrantedAuthority("ROLE_VIEWER")))).andExpect(status().isOk()).andExpect(jsonPath("$.data.logs.length()").value(0)).andExpect(jsonPath("$.data.candidatesScanned").value(0));
    }
    @Test void malformedBoundsAndSearchTextAreRejectedAsReadOnlyBadRequests()throws Exception {
        var auth=jwt().authorities(new SimpleGrantedAuthority("ROLE_VIEWER"));
        mvc.perform(get("/api/observability/logs?minutes=0").with(auth)).andExpect(status().isBadRequest());
        mvc.perform(get("/api/observability/logs?limit=101").with(auth)).andExpect(status().isBadRequest());
        mvc.perform(get("/api/observability/logs").param("query","PUBLIC\nINVALID").with(auth)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value("日志检索仅支持固定元数据字符，最多128字符和4个词"));
    }
}
