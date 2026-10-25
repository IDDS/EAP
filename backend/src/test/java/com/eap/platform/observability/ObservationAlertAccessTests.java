package com.eap.platform.observability;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.mockito.Mockito.*;

@SpringBootTest(properties={"spring.flyway.enabled=true","eap.security.mode=JWT","eap.security.issuer-uri=https://idp.invalid.example/eap","eap.security.jwk-set-uri=https://idp.invalid.example/jwks","eap.substrate.deploy-worker-enabled=false"})
@ActiveProfiles("test") @AutoConfigureMockMvc
class ObservationAlertAccessTests {
    @Autowired MockMvc mvc;
    @MockitoBean ObservationAlertService alerts;
    @ParameterizedTest @CsvSource({"VIEWER,true,false","OPERATOR,true,false","GOVERNANCE_ADMIN,true,true","PLATFORM_ADMIN,true,true","DATA_OWNER,false,false","NONE,false,false"})
    void metadataReadsAndWritesUseExistingMeCapabilities(String role,boolean read,boolean write)throws Exception {
        var auth=jwt().jwt(j->j.subject("fixture-subject")).authorities(new SimpleGrantedAuthority("ROLE_"+role));
        mvc.perform(get("/api/platform/me").with(auth)).andExpect(status().isOk()).andExpect(jsonPath("$.data.capabilities.viewConsole").value(read)).andExpect(jsonPath("$.data.capabilities.governance").value(write));
        mvc.perform(get("/api/observability/operations/1/alerts").with(auth)).andExpect(read?status().isOk():status().isForbidden());
        mvc.perform(post("/api/observability/operations/1/alerts/reconcile").with(auth)).andExpect(write?status().isOk():status().isForbidden());
        mvc.perform(post("/api/observability/operations/1/alerts/2/acknowledgement").with(auth).contentType(MediaType.APPLICATION_JSON).content("{\"factRevision\":1,\"factDigest\":\""+"a".repeat(64)+"\"}")).andExpect(write?status().isOk():status().isForbidden());
        if(write)verify(alerts).reconcile(1,"fixture-subject");
        else verify(alerts,never()).reconcile(anyLong(),anyString());
        clearInvocations(alerts);
    }
    @Test void anonymousCannotReadOrWriteAndInvalidVersionCannotReachService()throws Exception {
        mvc.perform(get("/api/observability/operations/1/alerts")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/observability/operations/1/alerts/reconcile")).andExpect(status().isUnauthorized());
        var admin=jwt().authorities(new SimpleGrantedAuthority("ROLE_PLATFORM_ADMIN"));
        mvc.perform(post("/api/observability/operations/1/alerts/2/acknowledgement").with(admin).contentType(MediaType.APPLICATION_JSON).content("{\"factRevision\":0,\"factDigest\":\"invalid\"}")).andExpect(status().isBadRequest());
        mvc.perform(put("/api/observability/operations/1/alerts/reconcile").with(admin)).andExpect(status().isForbidden());
        verifyNoInteractions(alerts);
    }
}
