package com.eap.platform.deployment;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Role rules for console deployment under JWT mode (SecurityConfig /api/agents/**). */
@SpringBootTest(properties = {
        "eap.security.mode=JWT",
        "eap.security.jwk-set-uri=https://idp.invalid.example/jwks",
        "spring.flyway.enabled=true",
        "eap.substrate.deploy-worker-enabled=false"
})
@ActiveProfiles("test")
@AutoConfigureMockMvc
class SubstrateDeploymentSecurityTests {
    private static final String DEPLOY = "{\"sourceType\":\"RUNNER\",\"systemPrompt\":\"x\",\"model\":\"deepseek-flash\"}";

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    SubstrateDeploymentService service;

    @Test
    void anonymousCallersAreRejected() throws Exception {
        mockMvc.perform(get("/api/agents/7/substrate/deployments")).andExpect(status().isUnauthorized());
        mockMvc.perform(deploy()).andExpect(status().isUnauthorized());
        verifyNoInteractions(service);
    }

    @Test
    void viewerCanReadButCannotDeployOrOperateInstances() throws Exception {
        when(service.list(7L)).thenReturn(List.of());
        mockMvc.perform(get("/api/agents/7/substrate/deployments").with(role("VIEWER"))).andExpect(status().isOk());
        mockMvc.perform(deploy().with(role("VIEWER"))).andExpect(status().isForbidden());
        mockMvc.perform(post("/api/agents/7/substrate/instances").with(role("VIEWER"))).andExpect(status().isForbidden());
        mockMvc.perform(delete("/api/agents/7/substrate/instances/a7-v1-abc123").with(role("VIEWER"))).andExpect(status().isForbidden());
        mockMvc.perform(post("/api/agents/7/substrate/instances/a7-v1-abc123/chat").with(role("VIEWER"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"message\":\"hi\"}")).andExpect(status().isForbidden());
        mockMvc.perform(post("/api/agents/7/substrate/deployments/1/retry").with(role("VIEWER"))).andExpect(status().isForbidden());
        verify(service).list(7L);
        org.mockito.Mockito.verifyNoMoreInteractions(service);
    }

    @Test
    void governanceAdminCannotDeploy() throws Exception {
        mockMvc.perform(deploy().with(role("GOVERNANCE_ADMIN"))).andExpect(status().isForbidden());
        verifyNoInteractions(service);
    }

    @Test
    void operatorDeploysAndIsRecordedAsTheRequester() throws Exception {
        mockMvc.perform(deploy().with(jwt().jwt(token -> token.subject("ops@example.com"))
                        .authorities(new SimpleGrantedAuthority("ROLE_OPERATOR"))))
                .andExpect(status().isOk());
        verify(service).submit(eq(7L), any(), eq("ops@example.com"));
    }

    private static MockHttpServletRequestBuilder deploy() {
        return post("/api/agents/7/substrate/deployments").contentType(MediaType.APPLICATION_JSON).content(DEPLOY);
    }

    private static org.springframework.test.web.servlet.request.RequestPostProcessor role(String role) {
        return jwt().authorities(new SimpleGrantedAuthority("ROLE_" + role));
    }
}
