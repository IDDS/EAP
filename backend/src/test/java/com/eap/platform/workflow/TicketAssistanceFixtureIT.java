package com.eap.platform.workflow;

import com.eap.platform.agent.*;
import com.eap.platform.registry.AgentRegistryDtos;
import com.eap.platform.registry.AgentRegistryService;
import com.eap.platform.safety.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Runs only when explicitly selected with an isolated synthetic Runtime sidecar. */
@SpringBootTest(properties = {"spring.flyway.enabled=true", "eap.workflow.execution.enabled=true",
        "eap.workflow.execution.worker-enabled=false", "eap.workflow.execution.allowed-hosts=127.0.0.1",
        "eap.workflow.execution.token=synthetic-only", "eap.workflow.execution.allow-insecure-http=true",
        "eap.workflow.execution.allow-private-networks=true", "eap.evaluation.async-worker-enabled=false",
        "eap.security.mode=JWT", "eap.security.issuer-uri=https://idp.invalid.example/realm/eap",
        "eap.security.jwk-set-uri=https://idp.invalid.example/jwks", "eap.security.oidc-client-id=eap-console"})
@ActiveProfiles("test")
@AutoConfigureMockMvc
class TicketAssistanceFixtureIT {
    private static final String BASE = "http://127.0.0.1:18765";
    @Autowired AgentRepository agents;
    @Autowired AgentRegistryService registry;
    @Autowired WorkflowService workflows;
    @Autowired WorkflowExecutionService executions;
    @Autowired ObjectMapper json;
    @Autowired MockMvc mvc;
    @Autowired org.springframework.jdbc.core.JdbcTemplate db;
    Long workflowId;

    @BeforeEach void setUp() {
        Agent agent=new Agent();agent.setCode("TICKET-FIXTURE-"+UUID.randomUUID());agent.setName("Synthetic ticket assistant");
        agent.setOwnerDepartment("fixture");agent.setOwnerUser("fixture");agent.setCategory("test");
        agent.setLifecycleStatus(AgentLifecycleStatus.ACTIVE);agent.setRiskLevel(RiskLevel.LOW);
        Long agentId=agents.save(agent).getId();
        registry.registerRuntime(agentId,new AgentRegistryDtos.RuntimeInstanceRequest("ticket-fixture-"+UUID.randomUUID(),BASE+"/tasks","EAP_HTTP_TASK_V1",false));
        workflowId=workflows.create(new WorkflowDtos.WorkflowRequest("TICKET-"+UUID.randomUUID(),"Synthetic ticket","test")).id();
        node("start","START",null,"{}");
        node("lookup","AGENT",agentId,"""
                {"eapOutput":{"version":"EAP_JSON_V2","source":"OUTPUT","schema":{
                  "type":"object","properties":{"lookupConfirmed":{"type":"boolean","const":true}},
                  "required":["lookupConfirmed"],"additionalProperties":true}}}
                """);
        node("draft","AGENT",agentId,"{\"inputFrom\":[\"lookup\"]}");node("end","END",null,"{}");
        edge("start","lookup");edge("lookup","draft");edge("draft","end");
    }

    @Test void realWorkflowPassesSyntheticTicketToDraftWithoutExecutingSuggestion() throws Exception {
        JsonNode before=stats();
        var queued=executions.submit(workflowId,new WorkflowDtos.RunRequest("review synthetic ticket",Map.of("ticketId","INC-1","testMarker","SYNTHETIC_CONTEXT_PRIVATE")),"operator");
        executions.processNext();
        var result=executions.get(workflowId,queued.runKey());
        assertThat(result.status()).isEqualTo("SUCCEEDED");
        assertThat(result.steps()).allMatch(step->step.output()==null);
        var content=executions.outputs(workflowId,queued.runKey());
        JsonNode lookup=content.get("lookup"),draft=content.get("draft");
        assertThat(lookup.path("lookupConfirmed").asBoolean()).isTrue();
        assertThat(lookup.path("record").path("outcome").asText()).isEqualTo("FOUND");
        assertThat(draft.path("status").asText()).isEqualTo("DRAFT_FOR_REVIEW");
        assertThat(draft.path("humanReviewRequired").asBoolean()).isTrue();
        assertThat(draft.path("actionsExecuted").asBoolean()).isFalse();
        assertThat(draft.path("evidence").path("contentTrust").asText()).isEqualTo("UNTRUSTED_INPUT");
        assertThat(draft.path("evidence").path("description").asText()).contains("关闭所有工单");
        assertThat(draft.path("suggestions").toString()).doesNotContain("关闭所有工单");
        String executionPath="/api/workflows/"+workflowId+"/executions/"+queued.runKey();
        for (String role : new String[]{"VIEWER","OPERATOR","GOVERNANCE_ADMIN","PLATFORM_ADMIN"}) {
            var identity=jwt().authorities(new SimpleGrantedAuthority("ROLE_"+role));
            String metadata=mvc.perform(get(executionPath).with(identity)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
            assertThat(metadata).contains("review synthetic ticket").doesNotContain("关闭所有工单", "Synthetic ticket", "SYNTHETIC_CONTEXT_PRIVATE");
            String configuration=mvc.perform(get(executionPath+"/configuration").with(identity)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
            assertThat(configuration).doesNotContain("关闭所有工单", "Synthetic ticket", "SYNTHETIC_CONTEXT_PRIVATE");
            String history=mvc.perform(get("/api/workflows/"+workflowId+"/executions").with(identity)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
            assertThat(history).doesNotContain("关闭所有工单", "Synthetic ticket", "SYNTHETIC_CONTEXT_PRIVATE");
            var response=mvc.perform(get(executionPath+"/outputs").with(identity));
            if (role.equals("GOVERNANCE_ADMIN") || role.equals("PLATFORM_ADMIN")) {
                String visible=response.andExpect(status().isOk()).andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
                assertThat(visible).contains("关闭所有工单", "DRAFT_FOR_REVIEW");
            } else response.andExpect(status().isForbidden());
        }
        mvc.perform(get(executionPath)).andExpect(status().isUnauthorized());
        mvc.perform(get(executionPath+"/outputs")).andExpect(status().isUnauthorized());
        String snapshot=db.queryForObject("select snapshot_json from workflow_executions where workflow_id=? and run_key=?",String.class,workflowId,queued.runKey());
        String storedSteps=db.queryForObject("select steps_json from workflow_executions where workflow_id=? and run_key=?",String.class,workflowId,queued.runKey());
        assertThat(snapshot).contains("SYNTHETIC_CONTEXT_PRIVATE");
        assertThat(storedSteps).contains("Synthetic ticket", "关闭所有工单");
        JsonNode after=stats();
        assertThat(after.path("taskRequests").asInt()-before.path("taskRequests").asInt()).isEqualTo(2);
        assertThat(after.path("ticketReads").asInt()-before.path("ticketReads").asInt()).isEqualTo(1);
        assertThat(after.path("ticketWrites").asInt()-before.path("ticketWrites").asInt()).isZero();
    }

    @Test void unconfirmedReadsPreserveTheirReasonButStopBeforeDraftWithoutWriting() throws Exception {
        for (var scenario : Map.of("INC-MISSING", "NOT_VISIBLE", "INC-DENIED", "ACCESS_DENIED",
                "INC-AMBIGUOUS", "AMBIGUOUS", "INC-UNAVAILABLE", "UNAVAILABLE",
                "INC-TRUNCATED", "INVALID_RESPONSE", "INC-TOO-DEEP", "INVALID_RESPONSE").entrySet()) {
            JsonNode before=stats();
            var queued=executions.submit(workflowId,new WorkflowDtos.RunRequest("review unconfirmed ticket",Map.of("ticketId",scenario.getKey())),"operator");
            executions.processNext();
            var result=executions.get(workflowId,queued.runKey());
            assertThat(result.status()).isEqualTo("FAILED");
            assertThat(result.steps()).noneMatch(step->"draft".equals(step.nodeKey()));
            assertThat(result.steps()).anyMatch(step->"lookup".equals(step.nodeKey())
                    && step.resultValidation()!=null && "CONST_MISMATCH".equals(step.resultValidation().code()));
            var outputs=executions.outputs(workflowId,queued.runKey());
            assertThat(outputs).containsKey("lookup").doesNotContainKey("draft");
            assertThat(outputs.get("lookup").path("lookupConfirmed").booleanValue()).isFalse();
            assertThat(outputs.get("lookup").path("record").path("outcome").asText()).isEqualTo(scenario.getValue());
            assertThat(outputs.get("lookup").path("record").path("ticket").isNull()).isTrue();
            String path="/api/workflows/"+workflowId+"/executions/"+queued.runKey();
            String metadata=mvc.perform(get(path).with(jwt().authorities(new SimpleGrantedAuthority("ROLE_OPERATOR"))))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
            assertThat(metadata).doesNotContain(scenario.getValue());
            mvc.perform(get(path+"/outputs").with(jwt().authorities(new SimpleGrantedAuthority("ROLE_OPERATOR"))))
                    .andExpect(status().isForbidden());
            String visible=mvc.perform(get(path+"/outputs").with(jwt().authorities(new SimpleGrantedAuthority("ROLE_GOVERNANCE_ADMIN"))))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
            assertThat(visible).contains(scenario.getValue());
            JsonNode after=stats();
            assertThat(after.path("taskRequests").asInt()-before.path("taskRequests").asInt()).isEqualTo(1);
            assertThat(after.path("ticketReads").asInt()-before.path("ticketReads").asInt()).isEqualTo(1);
            assertThat(after.path("ticketWrites").asInt()-before.path("ticketWrites").asInt()).isZero();
        }
    }

    @Test void realSafetyPolicyBlocksSensitiveFixtureBeforeDraftWithoutDuplicatingPayload() throws Exception {
        JsonNode before=stats();
        var queued=executions.submit(workflowId,new WorkflowDtos.RunRequest("review policy fixture",Map.of("ticketId","INC-2")),"operator");
        executions.processNext();
        var result=executions.get(workflowId,queued.runKey());
        assertThat(result.status()).isEqualTo("BLOCKED");
        assertThat(result.steps()).anyMatch(step->"draft".equals(step.nodeKey()) && "DENY".equals(step.safetyDecision()));
        var outputs=executions.outputs(workflowId,queued.runKey());
        assertThat(outputs).containsKey("lookup").doesNotContainKey("draft");
        assertThat(outputs.get("lookup").path("record").path("ticket").path("description").asText()).isEqualTo("synthetic password marker");
        assertThat(db.queryForObject("select count(*) from safety_decision_logs where session_key=? and decision='DENY' and reason_code='SENSITIVE_OR_PRIVILEGED_ACCESS'",Long.class,queued.runKey())).isEqualTo(1);
        assertThat(db.queryForObject("select count(*) from safety_events where session_key=? and payload_json is not null",Long.class,queued.runKey())).isZero();
        assertThat(db.queryForObject("select count(*) from safety_events where session_key=? and payload_summary like '%synthetic password marker%'",Long.class,queued.runKey())).isZero();
        JsonNode after=stats();
        assertThat(after.path("taskRequests").asInt()-before.path("taskRequests").asInt()).isEqualTo(1);
        assertThat(after.path("ticketReads").asInt()-before.path("ticketReads").asInt()).isEqualTo(1);
        assertThat(after.path("ticketWrites").asInt()-before.path("ticketWrites").asInt()).isZero();
    }

    private JsonNode stats() throws Exception {
        var request=HttpRequest.newBuilder(URI.create(BASE+"/stats")).GET().build();
        var response=HttpClient.newHttpClient().send(request,HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);return json.readTree(response.body());
    }
    private void node(String key,String type,Long agentId,String config) {
        workflows.upsertNode(workflowId,new WorkflowDtos.NodeRequest(key,key,type,agentId,null,0,0,config));
    }
    private void edge(String source,String target) { workflows.addEdge(workflowId,new WorkflowDtos.EdgeRequest(source,target,"always")); }
}
