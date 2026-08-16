package com.eap.platform.config;

import com.eap.platform.discovery.DiscoveryAgentV1Dtos;
import com.eap.platform.discovery.DiscoveryAgentV1Service;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "eap.security.mode=JWT",
        "eap.security.jwk-set-uri=https://idp.invalid.example/jwks",
        "eap.observability.ingestion-token=observation-secret",
        "eap.discovery.ingestion-token=discovery-secret",
        "spring.flyway.enabled=true"
})
@ActiveProfiles("test")
@AutoConfigureMockMvc
class JwtSecurityIntegrationTests {
    @Autowired
    MockMvc mockMvc;

    @Autowired
    DiscoveryAgentV1Service discoveryAgentService;

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
    void businessContentRequiresGovernanceOrPlatformAdmin() throws Exception {
        mockMvc.perform(get("/api/observability/content")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_VIEWER"))))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/observability/content")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_GOVERNANCE_ADMIN"))))
                .andExpect(status().isOk());
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

        mockMvc.perform(post("/api/discovery/connectors/kubernetes/watch")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(post("/api/discovery/connectors/kubernetes/watch")
                        .header("X-EAP-Discovery-Token", "discovery-secret")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"DELETED\",\"object\":{\"kind\":\"Pod\",\"metadata\":{\"name\":\"plain\"},\"spec\":{\"containers\":[]}}}"))
                .andExpect(status().isOk());

        String hostObservation = """
                {"sensorType":"WFP","endpointKey":"windows-01","hostName":"worker-01",
                 "operatingSystem":"Windows","collectorVersion":"host-observer/0.1","observations":[
                  {"processName":"agent-worker","processId":42,"provider":"OpenAI","modelName":"gpt-4o",
                   "remoteAddress":"104.18.1.1","remotePort":443,"transport":"TCP","direction":"OUTBOUND"}
                 ]}
                """;
        mockMvc.perform(post("/api/discovery/connectors/host/observations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(hostObservation))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(post("/api/discovery/connectors/host/observations")
                        .header("X-EAP-Discovery-Token", "discovery-secret")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(hostObservation))
                .andExpect(status().isOk());
    }

    @Test
    void enrolledDiscoveryAgentCanUploadPortableHostObservations() throws Exception {
        var token = discoveryAgentService.createEnrollmentToken(new DiscoveryAgentV1Dtos.EnrollmentTokenRequest(
                "default", "test", "test", "test", null, 15, 1, "platform-admin"
        ));
        var enrolled = discoveryAgentService.enroll(new DiscoveryAgentV1Dtos.EnrollRequest(
                token.token(), "host-observer-installation", "host-observer-fingerprint",
                "host-observer-worker", "svc-agent", "Windows", "amd64", "1.3.0", List.of("HOST_OBSERVER")
        ));
        String payload = """
                {"sensorType":"WFP","collectorVersion":"eap-discovery-agent/1.3.0","observations":[
                  {"processName":"agent-worker","processId":42,"provider":"OpenAI","modelName":"gpt-4o",
                   "remoteAddress":"104.18.1.1","remotePort":443,"transport":"TCP","direction":"OUTBOUND",
                   "identityKey":"process:host-observer-worker:agent-worker"}
                ]}
                """;

        mockMvc.perform(post("/api/discovery/v1/agent/host-observations")
                        .header("X-EAP-Agent-Credential", enrolled.credential())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isOk());

        var endpoint = discoveryAgentService.listAgents().stream()
                .filter(item -> "host-observer-worker".equals(item.hostName()))
                .findFirst().orElseThrow();
        assertThat(endpoint.agentVersion()).isEqualTo("1.3.0");
    }
}
