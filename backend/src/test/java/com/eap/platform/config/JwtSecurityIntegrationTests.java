package com.eap.platform.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "eap.security.mode=JWT",
        "eap.security.jwk-set-uri=https://idp.invalid.example/jwks",
        "eap.observability.ingestion-token=observation-secret"
})
@ActiveProfiles("test")
@AutoConfigureMockMvc
class JwtSecurityIntegrationTests {
    @Autowired
    MockMvc mockMvc;

    @Test
    void consoleReadRequiresJwtAndViewerRoleCanRead() throws Exception {
        mockMvc.perform(get("/api/gateway/status"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get("/api/gateway/status")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_VIEWER"))))
                .andExpect(status().isOk());
    }

    @Test
    void viewerCannotIssueControlCommands() throws Exception {
        mockMvc.perform(post("/api/control/commands")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_VIEWER")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void machineEndpointUsesDedicatedTokenWithoutUserJwt() throws Exception {
        mockMvc.perform(post("/api/observability/otlp/v1/traces")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(post("/api/observability/otlp/v1/traces")
                        .header("X-EAP-Observability-Token", "observation-secret")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"resourceSpans\":[]}"))
                .andExpect(status().isOk());
    }
}
