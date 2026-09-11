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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

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
    void taskCreationUsesAuthenticatedSubjectWhenRequesterIsMissing() throws Exception {
        mockMvc.perform(post("/api/tasks")
                        .with(jwt().jwt(token -> token.subject("employee@example.com"))
                                .authorities(new SimpleGrantedAuthority("ROLE_OPERATOR")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"agentId\":1,\"taskType\":\"GOVERNED_ANALYSIS\",\"inputSummary\":\"业务异常解释\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.requester").value("employee@example.com"));
    }

    @Test
    void viewerCanReadButCannotMutateBusinessConnectors() throws Exception {
        var viewer = jwt().authorities(new SimpleGrantedAuthority("ROLE_VIEWER"));
        mockMvc.perform(get("/api/management/connectors").with(viewer))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/management/connectors").with(viewer)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/management/connectors/1/sync").with(viewer))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/management/connectors/1/enabled").with(viewer).param("enabled", "false"))
                .andExpect(status().isForbidden());
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(
                        "/api/management/connectors/1").with(viewer)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
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
    void viewerCannotWriteEvaluationSamplesOrAnnotations() throws Exception {
        mockMvc.perform(post("/api/evaluations/datasets/REGRESSION/items")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_VIEWER")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isForbidden());

        mockMvc.perform(patch("/api/evaluations/datasets/REGRESSION/items/case-1/annotation")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_VIEWER")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/evaluations/datasets/REGRESSION/run")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_VIEWER")))
                        .param("limit", "10"))
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
    void paginatedContentKeepsRestrictedReadRoles() throws Exception {
        mockMvc.perform(get("/api/observability/content/page")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/observability/content/page").with(jwt().authorities(new SimpleGrantedAuthority("ROLE_VIEWER")))).andExpect(status().isForbidden());
        mockMvc.perform(get("/api/observability/content/page").with(jwt().authorities(new SimpleGrantedAuthority("ROLE_GOVERNANCE_ADMIN")))).andExpect(status().isOk());
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
    @Test
    void contentApprovalRequiresActualRolesAndDistinctAuthenticatedSubjects() throws Exception {
        var token = discoveryAgentService.createEnrollmentToken(new DiscoveryAgentV1Dtos.EnrollmentTokenRequest(
                "default", "test", "test", "test", null, 15, 1, "admin"));
        discoveryAgentService.enroll(new DiscoveryAgentV1Dtos.EnrollRequest(
                token.token(), "approval-installation", "approval-fingerprint", "approval-host",
                "svc-agent", "Linux", "amd64", "1.3.0", List.of("PROCESS")));
        var endpoint = discoveryAgentService.listAgents().stream()
                .filter(item -> "approval-host".equals(item.hostName())).findFirst().orElseThrow();
        String url = "/api/discovery/v1/admin/agents/" + endpoint.id() + "/content-approval";
        String platformApproval = "{\"approvalRole\":\"PLATFORM_ADMIN\",\"approvedBy\":\"forged-admin\"}";
        String ownerApproval = "{\"approvalRole\":\"DATA_OWNER\",\"approvedBy\":\"forged-owner\"}";

        mockMvc.perform(post(url).with(jwt().authorities(new SimpleGrantedAuthority("ROLE_OPERATOR")))
                        .contentType(MediaType.APPLICATION_JSON).content(platformApproval))
                .andExpect(status().isForbidden());
        mockMvc.perform(post(url).with(jwt().jwt(jwt -> jwt.subject("alice"))
                        .authorities(new SimpleGrantedAuthority("ROLE_PLATFORM_ADMIN")))
                        .contentType(MediaType.APPLICATION_JSON).content(ownerApproval))
                .andExpect(status().isForbidden());
        mockMvc.perform(post(url).with(jwt().jwt(jwt -> jwt.subject("alice"))
                        .authorities(new SimpleGrantedAuthority("ROLE_PLATFORM_ADMIN")))
                        .contentType(MediaType.APPLICATION_JSON).content(platformApproval))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.contentPlatformApprovedBy").value("alice"))
                .andExpect(jsonPath("$.data.contentCaptureStatus").value("PENDING_APPROVAL"));
        mockMvc.perform(post(url).with(jwt().jwt(jwt -> jwt.subject("alice"))
                        .authorities(new SimpleGrantedAuthority("ROLE_DATA_OWNER")))
                        .contentType(MediaType.APPLICATION_JSON).content(ownerApproval))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post(url).with(jwt().jwt(jwt -> jwt.subject("bob"))
                        .authorities(new SimpleGrantedAuthority("ROLE_DATA_OWNER")))
                        .contentType(MediaType.APPLICATION_JSON).content(ownerApproval))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.contentDataApprovedBy").value("bob"))
                .andExpect(jsonPath("$.data.contentCaptureStatus").value("ENABLED"));
        mockMvc.perform(post("/api/discovery/v1/admin/enrollment-tokens")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_DATA_OWNER")))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
    }

}
