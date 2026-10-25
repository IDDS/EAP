package com.eap.platform.platform;

import com.eap.platform.control.AgentControlService;
import com.eap.platform.deployment.SubstrateDeploymentService;
import com.eap.platform.discovery.DiscoveryAgentV1Service;
import com.eap.platform.evaluation.EvaluationDatasetItemService;
import com.eap.platform.evaluation.EvaluationService;
import com.eap.platform.governance.GovernancePolicyService;
import com.eap.platform.observability.ContentObservationService;
import com.eap.platform.workflow.WorkflowExecutionService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
        "eap.security.mode=JWT",
        "eap.security.issuer-uri=https://idp.invalid.example/realm/eap",
        "eap.security.jwk-set-uri=https://idp.invalid.example/jwks",
        "eap.security.oidc-client-id=eap-console",
        "eap.security.oidc-scopes=openid profile",
        "spring.flyway.enabled=true",
        "eap.substrate.deploy-worker-enabled=false"
})
@ActiveProfiles("test")
@AutoConfigureMockMvc
class PlatformPermissionSecurityTests {
    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @MockitoBean SubstrateDeploymentService deployments;
    @MockitoBean com.eap.platform.deployment.ChatSubmissionService chats;
    @MockitoBean com.eap.platform.monitoring.SubstrateMonitoringService substrateMonitoring;
    @MockitoBean com.eap.platform.ax.AxService ax;
    @MockitoBean AgentControlService controls;
    @MockitoBean com.eap.platform.registry.RuntimeInventoryService inventory;
    @MockitoBean com.eap.platform.registry.RuntimeConnectionService runtimeConnections;
    @MockitoBean com.eap.platform.registry.RuntimeToolGuardService runtimeTools;
    @MockitoBean com.eap.platform.registry.RuntimeToolPolicyService toolPolicies;
    @MockitoBean com.eap.platform.registry.ScopedToolService scopedTools;
    @MockitoBean WorkflowExecutionService workflows;
    @MockitoBean com.eap.platform.workflow.WorkflowSchedulingService scheduling;
    @MockitoBean com.eap.platform.workflow.WorkflowToolRecoveryService toolRecovery;
    @MockitoBean com.eap.platform.orchestration.PlanExecutionService planExecutions;
    @MockitoBean com.eap.platform.orchestration.OrchestrationService orchestration;
    @MockitoBean GovernancePolicyService policies;
    @MockitoBean ContentObservationService content;
    @MockitoBean DiscoveryAgentV1Service discovery;
    @MockitoBean EvaluationService evaluations;
    @MockitoBean EvaluationDatasetItemService annotations;
    @MockitoBean com.eap.platform.safety.GenseeSafetyService safety;

    @MockitoBean com.eap.platform.workflow.WorkflowStopService stops;
    @ParameterizedTest @CsvSource({"VIEWER,false", "OPERATOR,true", "GOVERNANCE_ADMIN,false", "PLATFORM_ADMIN,true"})
    void stopUsesRunWorkflowsCapabilityAndReadsUseViewConsole(String role, boolean allowed) throws Exception {
        mockMvc.perform(get("/api/workflows/7/executions/original/stop").with(identity(role))).andExpect(status().isOk());
        mockMvc.perform(post("/api/workflows/7/executions/original/stop").with(identity(role))
                .contentType(MediaType.APPLICATION_JSON).content("{\"requestKey\":\"stop-original\"}"))
                .andExpect(allowed ? status().isOk() : status().isForbidden());
        mockMvc.perform(get("/api/platform/me").with(identity(role)))
                .andExpect(jsonPath("$.data.capabilities.runWorkflows").value(allowed));
    }
    @Test void anonymousCannotReadOrRequestStop() throws Exception {
        mockMvc.perform(get("/api/workflows/7/executions/original/stop")).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/workflows/7/executions/original/stop").contentType(MediaType.APPLICATION_JSON)
                .content("{\"requestKey\":\"stop-original\"}")).andExpect(status().isUnauthorized());
    }

    @ParameterizedTest @CsvSource({"VIEWER,false","OPERATOR,false","GOVERNANCE_ADMIN,true","PLATFORM_ADMIN,true"})
    void scopedAuthorizationUsesSameGovernanceCapability(String role,boolean allowed)throws Exception {
        var identity=identity(role);
        mockMvc.perform(get("/api/registry/runtime-tools/scopes").param("instanceKey","synthetic").with(identity)).andExpect(status().isOk());
        String id="00000000-0000-4000-8000-000000000001";
        String body=objectMapper.writeValueAsString(Map.of("grantId",id,"holderKey","synthetic","resourceSet",List.of("poc_test"),"recipientSet",List.of("synthetic"),"maxDelta",1,"claimLimit",1,"expiresAt",java.time.Instant.now().plusSeconds(60)));
        mockMvc.perform(post("/api/registry/runtime-tools/scopes").with(identity).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(allowed?status().isOk():status().isForbidden());
        mockMvc.perform(post("/api/registry/runtime-tools/scopes/revoke").with(identity).contentType(MediaType.APPLICATION_JSON).content("{\"grantId\":\""+id+"\"}")).andExpect(allowed?status().isOk():status().isForbidden());
        mockMvc.perform(post("/api/runtime-access/v1/tools/scoped/execute").with(identity).contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isUnauthorized());
    }
    @ParameterizedTest @CsvSource({"VIEWER,true","OPERATOR,true","GOVERNANCE_ADMIN,true","PLATFORM_ADMIN,true","DATA_OWNER,false","NONE,false"})
    void originalOperationInspectionUsesViewConsoleCapability(String role,boolean allowed)throws Exception {
        var identity=identity(role);
        mockMvc.perform(get("/api/registry/runtime-tools/contracts").param("instanceKey","synthetic").with(identity)).andExpect(allowed?status().isOk():status().isForbidden());
        mockMvc.perform(get("/api/registry/runtime-tools/scopes/operations").param("instanceKey","synthetic").param("rootId","00000000-0000-4000-8000-000000000001").param("operationId","00000000-0000-4000-8000-000000000002").with(identity)).andExpect(allowed?status().isOk():status().isForbidden());
    }
    @ParameterizedTest @CsvSource({"VIEWER,true","OPERATOR,true","GOVERNANCE_ADMIN,true","PLATFORM_ADMIN,true","DATA_OWNER,false","NONE,false"})
    void workflowToolQueryUsesViewConsoleAndDoesNotExposeRawWorkflowOutputs(String role,boolean allowed)throws Exception {
        mockMvc.perform(get("/api/workflows/7/executions/original/tool-recovery").with(identity(role))).andExpect(allowed?status().isOk():status().isForbidden());
        mockMvc.perform(get("/api/workflows/scheduling").with(identity(role))).andExpect(allowed?status().isOk():status().isForbidden());
    }
    @Test void anonymousCannotReadWorkflowToolQuery()throws Exception {
        mockMvc.perform(get("/api/workflows/7/executions/original/tool-recovery")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/workflows/scheduling")).andExpect(status().isUnauthorized());
    }
    @Test void anonymousCannotReadToolContractsOrOriginalIntent()throws Exception {
        mockMvc.perform(get("/api/registry/runtime-tools/contracts").param("instanceKey","synthetic")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/registry/runtime-tools/scopes/operations").param("instanceKey","synthetic").param("rootId","synthetic").param("operationId","synthetic")).andExpect(status().isUnauthorized());
    }
    @Test
    void statusPublishesOnlyPublicOidcConfigurationAndMeRequiresAuthentication() throws Exception {
        mockMvc.perform(get("/api/platform/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.oidc.issuer").value("https://idp.invalid.example/realm/eap"))
                .andExpect(jsonPath("$.data.oidc.clientId").value("eap-console"))
                .andExpect(jsonPath("$.data.oidc.scopes").value("openid profile"))
                .andExpect(jsonPath("$.data.oidc.jwkSetUri").doesNotExist());
        mockMvc.perform(get("/api/platform/me")).andExpect(status().isUnauthorized());
    }

    @ParameterizedTest
    @CsvSource({
            "VIEWER,           true,  false, false, false, false",
            "OPERATOR,         true,  true,  false, false, true",
            "GOVERNANCE_ADMIN, true,  false, true,  false, true",
            "PLATFORM_ADMIN,   true,  true,  true,  true,  true",
            "DATA_OWNER,       false, false, false, true,  false",
            "NONE,             false, false, false, false, false"
    })
    void capabilitiesMatchRealEndpoints(String role, boolean view, boolean operate, boolean govern,
            boolean approve, boolean annotate) throws Exception {
        var identity = identity(role);
        var response = mockMvc.perform(get("/api/platform/me").with(identity))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.mode").value("JWT"))
                .andExpect(jsonPath("$.data.subject").value("permission-test"))
                .andReturn().getResponse();
        JsonNode user = objectMapper.readTree(response.getContentAsString()).path("data");
        assertEquals(role.equals("NONE") ? objectMapper.valueToTree(List.of()) : objectMapper.valueToTree(List.of(role)), user.path("roles"));
        JsonNode capabilities = user.path("capabilities");
        Map<String, Boolean> expected = new java.util.HashMap<>(Map.of(
                "viewConsole", view, "deployAgents", operate, "operateInstances", operate,
                "runWorkflows", operate, "governance", govern, "viewContent", govern,
                "approveContent", approve, "manageEvaluations", operate, "annotateEvaluations", annotate));
        expected.put("manageFeedback",annotate);
        expected.put("runAgentExperiments",role.equals("PLATFORM_ADMIN"));
        expected.put("manageBudgets",govern);
        expected.put("reviewExperimentUnknowns",govern);
        expected.put("manageSafety",govern);
        assertEquals(expected.size(), capabilities.size());
        expected.forEach((key, allowed) -> assertEquals(allowed.booleanValue(), capabilities.path(key).asBoolean(), key));

        for (String path : List.of("/summary", "/sessions", "/sessions/synthetic/events", "/sessions/synthetic/artifacts"))
            assertEndpoint(capabilities, "viewConsole", get("/api/safety" + path), identity);
        assertEndpoint(capabilities, "manageSafety", json(post("/api/safety/sessions"),
                "{\"sessionKey\":\"synthetic\",\"objective\":\"permission check\"}"), identity);
        assertEndpoint(capabilities, "manageSafety", json(post("/api/safety/events/evaluate"),
                "{\"sessionKey\":\"synthetic\",\"eventType\":\"TOOL_CALL\",\"action\":\"read\",\"target\":\"test\"}"), identity);
        assertEndpoint(capabilities, "manageSafety", json(post("/api/safety/artifacts"),
                "{\"sessionKey\":\"synthetic\",\"eventId\":1,\"artifactUri\":\"artifact://test\",\"artifactType\":\"text\"}"), identity);
        org.mockito.Mockito.verify(safety, org.mockito.Mockito.times(govern ? 1 : 0)).createSession(org.mockito.ArgumentMatchers.any());
        org.mockito.Mockito.verify(safety, org.mockito.Mockito.times(govern ? 1 : 0)).evaluate(org.mockito.ArgumentMatchers.any());
        org.mockito.Mockito.verify(safety, org.mockito.Mockito.times(govern ? 1 : 0)).registerArtifact(org.mockito.ArgumentMatchers.any());

        assertEndpoint(capabilities, "viewConsole", get("/api/agents/7/substrate/deployments"), identity);
        assertEndpoint(capabilities, "viewConsole", get("/api/agents/7/substrate/instances"), identity);
        assertEndpoint(capabilities, "viewConsole", get("/api/policies"), identity);
        assertEndpoint(capabilities, "viewConsole", get("/api/ax/tasks"), identity);
        assertEndpoint(capabilities, "viewContent", get("/api/ax/tasks/test/content"), identity);
        assertEndpoint(capabilities, "runWorkflows", json(post("/api/ax/tasks"), "{\"agentId\":7,\"requestKey\":\"key\",\"objective\":\"test\",\"mode\":\"ECHO\"}"), identity);
        assertEndpoint(capabilities, "operateInstances", post("/api/ax/tasks/test/suspend"), identity);
        assertEndpoint(capabilities, "viewConsole", get("/api/substrate/monitoring"), identity);
        assertEndpoint(capabilities, "viewConsole", get("/api/registry/runtime-tools/policy?instanceKey=eap-agents/outside"), identity);
        assertEndpoint(capabilities, "governance", json(put("/api/registry/runtime-tools/policy"),
                "{\"instanceKey\":\"eap-agents/outside\",\"expectedRevision\":0,\"enabled\":false,\"allowedTools\":[],\"claimLimit\":0}"), identity);
        assertEndpoint(capabilities, "viewConsole", get("/api/registry/runtime-tools?instanceKey=eap-agents/outside"), identity);
        assertEndpoint(capabilities, "viewConsole", get("/api/registry/runtime-inventory"), identity);
        assertEndpoint(capabilities, "viewConsole", get("/api/registry/runtime-connection?instanceKey=eap-agents/outside"), identity);
        assertEndpoint(capabilities, "deployAgents", json(post("/api/registry/runtime-connection/credential"),
                "{\"instanceKey\":\"eap-agents/outside\",\"requestKey\":\"00000000-0000-0000-0000-000000000001\"}"), identity);
        for (String action : List.of("verify", "revoke")) assertEndpoint(capabilities, "deployAgents",
                json(post("/api/registry/runtime-connection/"+action), "{\"instanceKey\":\"eap-agents/outside\"}"), identity);
        assertEndpoint(capabilities, "deployAgents", json(post("/api/registry/runtime-inventory/adopt"),
                "{\"scope\":\"eap-agents\",\"name\":\"outside\",\"uid\":\"uid-one\",\"template\":\"eap-agents/template\",\"agentId\":71}"), identity);
        assertEndpoint(capabilities, "deployAgents", json(post("/api/agents/7/substrate/deployments"),
                "{\"sourceType\":\"RUNNER\",\"systemPrompt\":\"x\",\"model\":\"deepseek-flash\"}"), identity);
        assertEndpoint(capabilities, "deployAgents", post("/api/agents/7/substrate/deployments/1/retry"), identity);
        assertEndpoint(capabilities, "deployAgents", get("/api/agents/7/substrate/deployments/requests/recovery-key"), identity);
        assertEndpoint(capabilities, "operateInstances", json(post("/api/agents/7/substrate/instances"),
                "{\"requestKey\":\"00000000-0000-4000-8000-000000000002\"}"), identity);
        assertEndpoint(capabilities, "operateInstances", delete("/api/agents/7/substrate/instances/actor"), identity);
        assertEndpoint(capabilities, "operateInstances", json(post("/api/agents/7/substrate/instances/actor/chat"), "{\"requestKey\":\"00000000-0000-4000-8000-000000000003\",\"message\":\"hi\"}"), identity);
        assertEndpoint(capabilities, "operateInstances", get("/api/agents/7/substrate/instances/actor/chat/00000000-0000-4000-8000-000000000003"), identity);
        assertEndpoint(capabilities, "operateInstances", json(post("/api/control/commands"),
                "{\"agentId\":7,\"commandType\":\"PAUSE\",\"runtimeInstanceKey\":\"actor\"}"), identity);
        assertEndpoint(capabilities, "operateInstances", post("/api/control/commands/command/retry"), identity);
        assertEndpoint(capabilities, "runWorkflows", json(post("/api/workflows/7/executions"), "{\"objective\":\"hello\"}"), identity);
        assertEndpoint(capabilities, "runWorkflows", json(post("/api/orchestration/plans/7/executions"), "{\"requestKey\":\"permission-proof\"}"), identity);
        assertEndpoint(capabilities, "runWorkflows", json(post("/api/orchestration/steps/7/output-contract"), "{\"outputContractJson\":null}"), identity);
        org.mockito.Mockito.verify(orchestration, org.mockito.Mockito.times(operate ? 1 : 0))
                .updateOutputContract(org.mockito.ArgumentMatchers.eq(7L), org.mockito.ArgumentMatchers.any());
        assertEndpoint(capabilities, "governance", json(post("/api/policies"),
                "{\"name\":\"test\",\"policyType\":\"PERMISSION\",\"scopeType\":\"GLOBAL\",\"ruleJson\":\"{}\",\"enforcementMode\":\"WARN\",\"enabled\":true}"), identity);
        assertEndpoint(capabilities, "viewContent", get("/api/observability/content"), identity);
        assertEndpoint(capabilities, "viewContent", get("/api/observability/content/page"), identity);
        assertEndpoint(capabilities, "viewContent", get("/api/workflows/7/executions/test/outputs"), identity);
        assertEndpoint(capabilities, "approveContent", json(post("/api/discovery/v1/admin/agents/7/content-approval"),
                "{\"approvalRole\":\"DATA_OWNER\",\"approvedBy\":\"permission-test\"}"), identity);
        assertEndpoint(capabilities, "manageEvaluations", json(post("/api/evaluations/datasets"), "{\"datasetKey\":\"test\",\"name\":\"test\"}"), identity);
        assertEndpoint(capabilities, "annotateEvaluations", json(patch("/api/evaluations/datasets/test/items/item/annotation"),
                "{\"groundTruthLabel\":\"PASS\"}"), identity);
    }

    @ParameterizedTest @CsvSource({"VIEWER", "OPERATOR", "DATA_OWNER", "NONE"})
    void safetyWritesAreDeniedBeforeParsingOrServiceSideEffects(String role) throws Exception {
        for (String path : List.of("/sessions", "/events/evaluate", "/artifacts"))
            mockMvc.perform(json(post("/api/safety" + path), "not-json").with(identity(role))).andExpect(status().isForbidden());
        org.mockito.Mockito.verifyNoInteractions(safety);
    }

    @Test void anonymousCannotReadOrWriteSafety() throws Exception {
        mockMvc.perform(get("/api/safety/summary")).andExpect(status().isUnauthorized());
        for (String path : List.of("/sessions", "/events/evaluate", "/artifacts"))
            mockMvc.perform(json(post("/api/safety" + path), "{}")).andExpect(status().isUnauthorized());
        org.mockito.Mockito.verifyNoInteractions(safety);
    }

    @ParameterizedTest @CsvSource({"VIEWER", "OPERATOR", "GOVERNANCE_ADMIN", "PLATFORM_ADMIN", "DATA_OWNER", "NONE"})
    void unmappedSafetyMethodsAndPathsCannotFallThroughToAuthenticated(String role) throws Exception {
        for (String path : List.of("/sessions", "/events/evaluate", "/artifacts")) {
            mockMvc.perform(json(put("/api/safety" + path), "{}").with(identity(role))).andExpect(status().isForbidden());
            mockMvc.perform(delete("/api/safety" + path).with(identity(role))).andExpect(status().isForbidden());
        }
        mockMvc.perform(post("/api/safety/future-write").with(identity(role))).andExpect(status().isForbidden());
        org.mockito.Mockito.verifyNoInteractions(safety);
    }

    @Test
    void capabilitiesCombineMultipleRoles() throws Exception {
        mockMvc.perform(get("/api/platform/me").with(jwt().authorities(
                        new SimpleGrantedAuthority("ROLE_VIEWER"), new SimpleGrantedAuthority("ROLE_DATA_OWNER"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.capabilities.viewConsole").value(true))
                .andExpect(jsonPath("$.data.capabilities.approveContent").value(true))
                .andExpect(jsonPath("$.data.capabilities.deployAgents").value(false));
    }

    private void assertEndpoint(JsonNode capabilities, String capability, MockHttpServletRequestBuilder request,
            RequestPostProcessor identity) throws Exception {
        assertTrue(capabilities.has(capability), capability);
        int responseStatus = mockMvc.perform(request.with(identity)).andReturn().getResponse().getStatus();
        assertEquals(capabilities.path(capability).asBoolean() ? 200 : 403, responseStatus, capability + " " + request);
    }

    private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request, String body) {
        return request.contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private static RequestPostProcessor identity(String role) {
        return jwt().jwt(token -> token.subject("permission-test"))
                .authorities(role.equals("NONE") ? List.of() : List.of(new SimpleGrantedAuthority("ROLE_" + role)));
    }
}
