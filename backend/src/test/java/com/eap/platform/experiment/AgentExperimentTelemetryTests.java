package com.eap.platform.experiment;

import com.eap.platform.agent.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"spring.flyway.enabled=true","eap.security.mode=JWT","eap.security.issuer-uri=https://idp.invalid.example/realm/eap","eap.security.jwk-set-uri=https://idp.invalid.example/jwks","eap.experiment.enabled=true","eap.experiment.worker-initial-delay-ms=3600000","eap.substrate.deploy-worker-enabled=false"})
@ActiveProfiles("test") @AutoConfigureMockMvc
class AgentExperimentTelemetryTests {
    @Autowired AgentExperimentService experiments;
    @Autowired AgentExperimentTelemetryService telemetry;
    @Autowired AgentService agents;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    @Autowired MockMvc mvc;
    @MockitoBean AgentExperimentClient client;
    Long agentId, experimentId, runtimeId;
    String instance, endpoint, template;
    AgentExperimentDtos.Summary experiment;

    @BeforeEach void setup() {
        agentId=agents.create(new AgentDtos.AgentRequest("TRACE-"+UUID.randomUUID(),"Telemetry test","","Test","Owner","Test",RiskLevel.LOW)).id();
        agents.updateStatus(agentId,AgentLifecycleStatus.ACTIVE);
        instance="eap-agents/a"+agentId+"-v1-test"; endpoint="substrate://"+instance; template="eap-agents/trace-"+agentId;
        jdbc.update("insert into agent_runtime_instances(agent_id,instance_key,endpoint,protocol,dispatch_authenticated,status,load_score,platform_actor_uid,platform_actor_template,created_at,updated_at) values(?,?,?,'SUBSTRATE',false,'ONLINE',0,?,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",agentId,instance,endpoint,"uid-"+agentId,template);
        runtimeId=jdbc.queryForObject("select id from agent_runtime_instances where instance_key=?",Long.class,instance);
        jdbc.update("insert into substrate_agent_deployments(deployment_key,agent_id,version,source_type,status,config_json,image_ref,atespace,template_name,instance_key,steps_json,requested_by,created_at,updated_at) values(?,?,1,'IMAGE','READY','{}',?,'eap-agents',?,?,'[]','test',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)","trace-deploy-"+agentId,agentId,"registry.test/runner@sha256:"+"b".repeat(64),"trace-"+agentId,instance);
        var profile=mapper.createObjectNode().put("protocol","EAP_STATELESS_PROMPT_V1").put("agentId",agentId.toString()).put("model","test-model").put("systemPrompt","private baseline").put("maxTokens",128).put("profileDigest","a".repeat(64)).put("context","EMPTY").put("tools","DISABLED");
        when(client.profile(endpoint)).thenReturn(new AgentExperimentClient.RuntimeProfile(profile,"uid-"+agentId,template));
        experiment=experiments.submit(new AgentExperimentDtos.Submit(agentId,instance,"a".repeat(64),"private candidate",128,List.of(new AgentExperimentDtos.Sample("private input","private expected")),true,UUID.randomUUID().toString()),"test");
        experimentId=experiment.id();
        clearInvocations(client);
    }

    @AfterEach void clean() {
        jdbc.update("delete from trace_span_observations where agent_id=?",agentId);
        jdbc.update("delete from model_invocation_events where agent_id=?",agentId);
        jdbc.update("delete from experiment_diagnostic_receipts where trial_id in (select id from agent_experiment_trials where experiment_id=?)",experimentId);
        jdbc.update("delete from experiment_diagnostic_decisions where trial_id in (select id from agent_experiment_trials where experiment_id=?)",experimentId);
        jdbc.update("delete from agent_experiment_trials where experiment_id=?",experimentId);
        jdbc.update("delete from agent_experiment_variants where experiment_id=?",experimentId);
        jdbc.update("delete from agent_experiments where id=?",experimentId);
    }
    void state(int index,String state) { jdbc.update("update agent_experiment_trials set status=? where id=?",state,experiment.trials().get(index).id()); }
    void span(int index,String kind,String name,Long agent,String endpointKey) {
        if("MODEL".equals(kind)) {
            jdbc.update("insert into model_invocation_events(event_key,trace_id,agent_id,endpoint_key,observation_source,governance_covered,provider,model_name,status,input_tokens,output_tokens,cached_input_tokens,reasoning_tokens,latency_ms,money_cost,currency,tool_call_count,mcp_server_count,observed_at,created_at) values(?,?,?,?,'GATEWAY',true,'test','test-model','SUCCEEDED',1,1,0,0,1,0,'USD',0,0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",UUID.randomUUID().toString(),experiment.trials().get(index).traceId(),agent,endpointKey);
            return;
        }
        jdbc.update("insert into trace_span_observations(trace_id,span_id,endpoint_key,agent_id,span_name,span_kind,duration_ms,observed_at,created_at) values(?,?,?,?,?,?,1,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",experiment.trials().get(index).traceId(),UUID.randomUUID().toString(),endpointKey,agent,name,kind);
    }
    void span(int index,String kind) { span(index,kind,"AGENT".equals(kind)?"agent.experiment":"chat",agentId,"runtime-"+runtimeId); }

    @Test void completeRequiresOneAgentAndModelForEverySuccessfulTrial() {
        for(int i=0;i<2;i++){state(i,"SUCCEEDED");span(i,"AGENT");span(i,"MODEL");}
        var before=experiments.get(experimentId);
        var result=telemetry.get(experimentId);
        assertThat(result.identityEvidence()).isEqualTo("FROZEN_REGISTRATION_MATCH");
        assertThat(result.registeredTrials()).isEqualTo(2);assertThat(result.completeTrials()).isEqualTo(2);
        assertThat(result.trials()).allMatch(t->t.agentSpans()==1&&t.modelInvocations()==1&&t.successfulModelInvocations()==1&&"COMPLETE".equals(t.coverage()));
        assertThat(experiments.get(experimentId)).isEqualTo(before);verifyNoInteractions(client);
    }

    @Test void missingAgentAndModelEvidenceIsExplicitWithoutRepair() {
        state(0,"SUCCEEDED");state(1,"SUCCEEDED");span(0,"MODEL");span(1,"AGENT");
        assertThat(telemetry.get(experimentId).trials()).extracting(AgentExperimentTelemetryService.TrialEvidence::coverage).containsExactly("MISSING_AGENT","MISSING_MODEL");
        assertThat(telemetry.get(experimentId).completeTrials()).isZero();verifyNoInteractions(client);
    }

    @Test void foreignAgentEndpointAndAgentOperationCannotCompleteEvidence() {
        state(0,"SUCCEEDED");span(0,"AGENT","agent.experiment",null,"runtime-"+runtimeId);
        span(0,"AGENT","agent.experiment",agentId,"runtime-foreign");
        span(0,"AGENT","agent.task",agentId,"runtime-"+runtimeId);
        span(0,"MODEL","chat",agentId,"runtime-foreign");
        assertThat(telemetry.get(experimentId).trials().get(0).coverage()).isEqualTo("MISSING_BOTH");
    }

    @Test void duplicateEvidenceRetainsActualCounts() {
        state(0,"SUCCEEDED");span(0,"AGENT");span(0,"AGENT");span(0,"MODEL");
        var t=telemetry.get(experimentId).trials().get(0);assertThat(t.agentSpans()).isEqualTo(2);assertThat(t.coverage()).isEqualTo("DUPLICATE_EVIDENCE");
    }

    @ParameterizedTest @ValueSource(strings={"UNKNOWN","FAILED"})
    void spansDoNotTurnUnconfirmedExecutionIntoSuccess(String status) {
        state(0,status);span(0,"AGENT");span(0,"MODEL");
        var result=telemetry.get(experimentId);assertThat(result.completeTrials()).isZero();assertThat(result.trials().get(0).executionStatus()).isEqualTo(status);assertThat(result.trials().get(0).coverage()).isEqualTo("EXECUTION_UNCONFIRMED");
    }

    @Test void pendingAndCancelledKeepTheRegisteredDenominator() {
        state(1,"CANCELLED");var result=telemetry.get(experimentId);assertThat(result.registeredTrials()).isEqualTo(2);
        assertThat(result.trials()).extracting(AgentExperimentTelemetryService.TrialEvidence::coverage).containsExactly("PENDING","NOT_EXPECTED");
        span(1,"MODEL");assertThat(telemetry.get(experimentId).trials().get(1).coverage()).isEqualTo("UNEXPECTED_EVIDENCE");
    }

    @ParameterizedTest @ValueSource(strings={"UID","TEMPLATE","ENDPOINT","UNBOUND"})
    void replacedOrUnboundIdentityDoesNotInventZeroCounts(String change) {
        state(0,"SUCCEEDED");span(0,"AGENT");span(0,"MODEL");
        String column=switch(change){case "UID"->"platform_actor_uid";case "TEMPLATE"->"platform_actor_template";case "ENDPOINT"->"endpoint";default->"platform_actor_uid";};
        jdbc.update("update agent_runtime_instances set "+column+"=? where id=?","UNBOUND".equals(change)?null:"changed",runtimeId);
        var result=telemetry.get(experimentId);assertThat(result.identityEvidence()).isEqualTo("UNCONFIRMED");
        assertThat(result.trials()).allMatch(t->t.agentSpans()==null&&t.modelInvocations()==null&&t.successfulModelInvocations()==null&&"IDENTITY_UNCONFIRMED".equals(t.coverage()));verifyNoInteractions(client);
    }

    @Test void otlpOrUntrustedModelProjectionCannotReplaceGatewayReceipt() {
        state(0,"SUCCEEDED");span(0,"AGENT");span(0,"MODEL");
        jdbc.update("update model_invocation_events set observation_source='OTLP' where agent_id=?",agentId);
        assertThat(telemetry.get(experimentId).trials().get(0).coverage()).isEqualTo("MISSING_MODEL");
        jdbc.update("insert into trace_span_observations(trace_id,span_id,endpoint_key,agent_id,span_name,span_kind,duration_ms,observed_at,created_at) values(?,?,?,?,'chat','MODEL',1,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",experiment.trials().get(0).traceId(),UUID.randomUUID().toString(),"runtime-"+runtimeId,agentId);
        assertThat(telemetry.get(experimentId).trials().get(0).modelInvocations()).isZero();
    }

    @Test void failedGatewayEventDoesNotConfirmSuccessfulBusinessExecution() {
        state(0,"SUCCEEDED");span(0,"AGENT");span(0,"MODEL");
        jdbc.update("update model_invocation_events set status='FAILED' where agent_id=?",agentId);
        var trial=telemetry.get(experimentId).trials().get(0);assertThat(trial.modelInvocations()).isEqualTo(1);assertThat(trial.successfulModelInvocations()).isZero();assertThat(trial.coverage()).isEqualTo("MODEL_STATUS_UNCONFIRMED");
    }

    @ParameterizedTest @ValueSource(strings={"VIEWER","OPERATOR","GOVERNANCE_ADMIN","PLATFORM_ADMIN"})
    void fourRolesReadOnlyMetadataWithoutContentOrRuntimeCalls(String role)throws Exception {
        var identity=jwt().authorities(new SimpleGrantedAuthority("ROLE_"+role));
        var before=experiments.get(experimentId);
        var body=mvc.perform(get("/api/agent-experiments/"+experimentId+"/telemetry").with(identity)).andExpect(status().isOk()).andExpect(jsonPath("$.data.registeredTrials").value(2)).andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain("private baseline","private candidate","private input","private expected","profileDigest","usage","accessToken");
        assertThat(experiments.get(experimentId)).isEqualTo(before);verifyNoInteractions(client);
    }
}
