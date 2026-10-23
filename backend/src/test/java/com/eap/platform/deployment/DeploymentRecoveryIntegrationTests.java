package com.eap.platform.deployment;

import com.eap.platform.agent.*;
import com.eap.platform.registry.RuntimeConnectionProperties;
import com.eap.platform.safety.*;
import com.eap.platform.substrate.*;
import com.eap.platform.workflow.WorkflowExecutionProperties;
import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {"spring.flyway.enabled=true", "eap.security.mode=JWT",
        "eap.security.jwk-set-uri=https://idp.invalid.example/jwks", "eap.substrate.deploy-worker-enabled=false"})
@ActiveProfiles("test") @AutoConfigureMockMvc
class DeploymentRecoveryIntegrationTests {
    @Autowired MockMvc api;
    @Autowired ObjectMapper mapper;
    @Autowired AgentService agents;
    @Autowired SubstrateDeploymentService service;
    @Autowired SubstrateDeploymentRepository repository;
    @Autowired SubstrateProperties properties;
    @Autowired WorkflowExecutionProperties workflow;
    @Autowired RuntimeConnectionProperties identity;
    @Autowired JdbcTemplate db;
    @MockitoBean SubstrateClient nativeClient;
    @MockitoBean PlatformRuntimeCreationService owned;
    @MockitoBean GenseeSafetyService safety;
    long agentId;
    String key;

    @BeforeEach void setup() {
        var agent = agents.create(new AgentDtos.AgentRequest("RECOVERY-"+UUID.randomUUID(),"Recovery fixture","","Test","Owner","Test",RiskLevel.LOW));
        agentId = agent.id(); agents.updateStatus(agentId,AgentLifecycleStatus.ACTIVE);
        key = UUID.randomUUID().toString();
        properties.setEnabled(true); properties.setRunnerImage("localhost:5001/test@sha256:"+"a".repeat(64));
        properties.setGatewayHost("backend.eap.svc");
        workflow.setEnabled(true); workflow.setAllowedHosts("atenet-router.ate-system.svc");
        identity.setSigningKey("synthetic-recovery-signing-material-long-enough"); identity.setDeliveryPrivateKey("synthetic-private"); identity.setDeliveryPublicKey("synthetic-public");
        when(safety.evaluate(any())).thenReturn(new SafetyDtos.SafetyEvaluationResponse(null,
                new SafetyDtos.DecisionResponse(null,null,null,SafetyDecision.ALLOW,null,null,null,null,null,null)));
        when(nativeClient.goldenStatus(any(),any())).thenReturn(new SubstrateClient.GoldenStatus(true,null));
        when(owned.create(any(),anyString(),any())).thenReturn("eap-agents/synthetic-first-instance");
    }
    @AfterEach void cleanup() {
        db.update("delete from substrate_agent_deployments where agent_id=?",agentId);
    }
    String path() { return "/api/agents/"+agentId+"/substrate/deployments"; }
    String payload(String prompt) throws Exception {
        return mapper.writeValueAsString(Map.of("sourceType","RUNNER","systemPrompt",prompt,"model","deepseek-flash","requestKey",key));
    }
    org.springframework.test.web.servlet.request.RequestPostProcessor operator() {
        return jwt().jwt(t->t.subject("synthetic-operator")).authorities(new SimpleGrantedAuthority("ROLE_OPERATOR"));
    }
    JsonNode submit() throws Exception {
        return mapper.readTree(api.perform(post(path()).with(operator()).contentType(MediaType.APPLICATION_JSON).content(payload("private configuration")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data");
    }
    JsonNode receipt() throws Exception {
        return mapper.readTree(api.perform(get(path()+"/requests/"+key).with(operator())).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString()).path("data");
    }
    @Test void lostResponseReplayAndReadOnlyQueryKeepOneVersionAndFirstInstance() throws Exception {
        submit(); // Deliberately discard the first accepted response, like a lost client reply.
        String original=receipt().path("deploymentKey").asText();
        assertThat(submit().path("deploymentKey").asText()).isEqualTo(original);
        assertThat(db.queryForObject("select count(*) from substrate_agent_deployments where agent_id=?",Integer.class,agentId)).isEqualTo(1);
        verifyNoInteractions(nativeClient,owned);
        for(int i=0;i<4;i++) service.processNext();
        assertThat(receipt().path("status").asText()).isEqualTo("READY");
        assertThat(submit().path("deploymentKey").asText()).isEqualTo(original);
        verify(owned,times(1)).create(any(),anyString(),any());
        verify(nativeClient,times(1)).createTemplate(any());
        verify(nativeClient,never()).createActor(any(),any());
    }
    @Test void queryFindsOldVersionBeyondListWithoutReturningConfiguration() throws Exception {
        submit(); var original=repository.findByAgentIdAndSubmissionKey(agentId,key).orElseThrow();
        for(int i=2;i<=22;i++) {
            var d=new SubstrateDeployment(); d.deploymentKey="dep-"+UUID.randomUUID();d.agentId=agentId;d.version=i;
            d.sourceType=original.sourceType;d.configJson=original.configJson;d.atespace=original.atespace;
            d.templateName="synthetic-v"+i;d.requestedBy="synthetic-operator";d.submissionKey="other-"+i;d.status="READY";
            repository.saveAndFlush(d);
        }
        assertThat(service.list(agentId)).hasSize(20).noneMatch(d->d.version()==1);
        properties.setEnabled(false); agents.updateStatus(agentId,AgentLifecycleStatus.RETIRED);
        var r=receipt();assertThat(r.path("version").asInt()).isEqualTo(1);
        assertThat(r.toString()).doesNotContain("private configuration","config","systemPrompt","identityPublicKey");
        verifyNoInteractions(nativeClient,owned);
    }
    @Test void absenceDoesNotEraseIdentityOrPreventALaterOriginalCommit() throws Exception {
        assertThat(receipt().path("status").asText()).isEqualTo("NOT_FOUND");
        submit();assertThat(receipt().path("version").asInt()).isEqualTo(1);
        long originalId=agentId;agentId=-7;
        assertThat(receipt().path("status").asText()).isEqualTo("NOT_FOUND");agentId=originalId;
        verifyNoInteractions(nativeClient,owned);
    }
    @Test void changedConfigurationConflictsAndOriginalReceiptSurvives() throws Exception {
        var first=submit();api.perform(post(path()).with(operator()).contentType(MediaType.APPLICATION_JSON).content(payload("changed")))
                .andExpect(status().isConflict());
        assertThat(receipt().path("deploymentKey").asText()).isEqualTo(first.path("deploymentKey").asText());
        assertThat(repository.findTop20ByAgentIdOrderByVersionDesc(agentId)).hasSize(1);
        verifyNoInteractions(nativeClient,owned);
    }
    @Test void concurrentSameKeyAdmissionsCannotPersistTwoVersions() throws Exception {
        var gate=new CountDownLatch(2);
        when(safety.evaluate(any())).thenAnswer(call->{gate.countDown();assertThat(gate.await(10,TimeUnit.SECONDS)).isTrue();
            return new SafetyDtos.SafetyEvaluationResponse(null,new SafetyDtos.DecisionResponse(null,null,null,SafetyDecision.ALLOW,null,null,null,null,null,null));});
        String body=payload("private configuration");var pool=Executors.newFixedThreadPool(2);
        try {
            Callable<Integer> attempt=()->api.perform(post(path()).with(operator()).contentType(MediaType.APPLICATION_JSON).content(body)).andReturn().getResponse().getStatus();
            var a=pool.submit(attempt);var b=pool.submit(attempt);
            assertThat(List.of(a.get(30,TimeUnit.SECONDS),b.get(30,TimeUnit.SECONDS))).contains(200);
        } finally { pool.shutdownNow(); }
        assertThat(repository.findTop20ByAgentIdOrderByVersionDesc(agentId)).hasSize(1);
        assertThat(receipt().path("version").asInt()).isEqualTo(1);verifyNoInteractions(nativeClient,owned);
    }
}
