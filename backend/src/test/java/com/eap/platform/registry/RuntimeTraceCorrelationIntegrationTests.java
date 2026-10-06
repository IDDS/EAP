package com.eap.platform.registry;

import com.eap.platform.agent.*;
import com.eap.platform.budget.BudgetLedger;
import com.eap.platform.config.UnauthorizedException;
import com.eap.platform.gateway.*;
import com.eap.platform.substrate.*;
import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import java.time.Instant;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest(properties={"spring.flyway.enabled=true","eap.budget.enabled=true","eap.gateway.enabled=true","eap.gateway.upstream-base-url=http://synthetic.invalid","eap.substrate.enabled=true","eap.substrate.deploy-worker-enabled=false","eap.runtime.integration.signing-key=synthetic-correlation-signing-material-only-for-tests"})
@ActiveProfiles("test")
class RuntimeTraceCorrelationIntegrationTests {
    @Autowired RuntimeAccessController controller;
    @Autowired RuntimeConnectionService connections;
    @Autowired RuntimeConnectionProperties config;
    @Autowired AgentRuntimeInstanceRepository runtimes;
    @Autowired RuntimeConnectionRepository connectionRows;
    @Autowired AgentService agents;
    @Autowired JdbcTemplate db;
    @Autowired ObjectMapper json;
    @Autowired BudgetLedger budgets;
    @MockitoBean SubstrateClient substrate;
    @MockitoBean OpenAiUpstreamClient upstream;
    AgentRuntimeInstance owner,other;
    RuntimeConnection lease;
    Long agent,experiment;
    String first,second;
    final Map<String,AgentRuntimeInstance> nativeActors=new HashMap<>();

    @BeforeEach void setup()throws Exception {
        var a=agents.create(new AgentDtos.AgentRequest("CORRELATION-"+UUID.randomUUID(),"Trace test","","Test","Owner","Test",RiskLevel.LOW));
        agent=a.id();agents.updateStatus(agent,AgentLifecycleStatus.ACTIVE);
        owner=runtime();other=runtime();
        when(substrate.inspect(any())).thenAnswer(i->{var ref=i.getArgument(0,SubstrateActorRef.class);var r=nativeActors.get(ref.routingValue());return new SubstrateClient.ActorInventory(ref.atespace(),ref.name(),r.getBoundActorUid(),r.getBoundActorTemplate(),"RUNNING");});
        lease=connectionRows.findByInstanceKey(owner.instanceKey).orElseThrow();
        db.update("insert into agent_experiments(agent_id,instance_key,actor_uid,template,endpoint,image_ref,deployment_version,submission_key,request_fingerprint,profile_digest,profile_json,samples_json,max_tokens,requested_by,status,created_at,updated_at) values(?,?,?,?,?,? ,1,?,'fingerprint',?,'{}','[]',4,'test','RUNNING',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",agent,owner.instanceKey,owner.getBoundActorUid(),owner.getBoundActorTemplate(),owner.endpoint,"image@sha256:"+"a".repeat(64),UUID.randomUUID().toString(),"b".repeat(64));
        experiment=db.queryForObject("select max(id) from agent_experiments where agent_id=?",Long.class,agent);
        db.update("insert into agent_experiment_variants(experiment_id,variant_name,config_digest,system_prompt) values(?,'BASELINE',?,'public')",experiment,"c".repeat(64));
        long variant=db.queryForObject("select max(id) from agent_experiment_variants where experiment_id=?",Long.class,experiment);
        first=UUID.randomUUID().toString().replace("-","");second=UUID.randomUUID().toString().replace("-","");
        for(int n=0;n<2;n++)db.update("insert into agent_experiment_trials(experiment_id,variant_id,sample_index,status,task_key,trace_id,usage_source,started_at) values(?,?,?,'RUNNING',?,?,'UNKNOWN',CURRENT_TIMESTAMP)",experiment,variant,n,"experiment:"+experiment+":"+n,n==0?first:second);
        when(upstream.chatCompletions(any())).thenReturn(ResponseEntity.ok(json.readTree("{\"choices\":[{\"message\":{\"content\":\"ok\"},\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":2,\"completion_tokens\":1}}")));
    }
    AgentRuntimeInstance runtime(){
        var r=new AgentRuntimeInstance();r.agentId=agent;r.instanceKey="eap-agents/correlation-"+UUID.randomUUID().toString().replace("-","");r.endpoint="substrate://"+r.instanceKey;r.protocol="SUBSTRATE";r.platformActorUid=UUID.randomUUID().toString();r.platformActorTemplate="eap-agents/template-"+UUID.randomUUID();r=runtimes.saveAndFlush(r);nativeActors.put(r.instanceKey,r);
        var c=new RuntimeConnection();c.instanceKey=r.instanceKey;c.generation=UUID.randomUUID().toString();c.expiresAt=Instant.now().plusSeconds(900);c.verifiedAt=Instant.now();connectionRows.saveAndFlush(c);return r;
    }
    MockHttpServletRequest request(AgentRuntimeInstance r,String trace){
        var c=connectionRows.findByInstanceKey(r.instanceKey).orElseThrow();var req=new MockHttpServletRequest();req.addHeader("X-EAP-Runtime-Key",r.instanceKey);req.addHeader("X-EAP-Runtime-Token",RuntimeConnectionService.credentialToken(config,r,c.generation,c.credentialRevision));req.addHeader("X-EAP-Trace-Id",trace);return req;
    }
    JsonNode body(){return json.createObjectNode().put("model","synthetic-model").put("max_tokens",4).set("messages",json.createArrayNode().add(json.createObjectNode().put("role","user").put("content","public sample")));}

    @Test void ownedIssuedTraceSharesExperimentBudgetAndOtlpKeepsIdentityIsolation()throws Exception {
        budgets.setLimit(new BudgetLedger.Limit("EXPERIMENT",experiment.toString(),"MODEL_CALLS",1L,0,0),"test");
        assertThat(controller.chat(body(),request(owner,first)).getStatusCode().value()).isEqualTo(200);
        var row=budgets.dashboard().reservations().stream().filter(r->agent.equals(r.agentId())).findFirst().orElseThrow();
        assertThat(row.experimentId()).isEqualTo(experiment);assertThat(row.traceId()).isEqualTo(first);assertThat(row.state()).isEqualTo("SETTLED");
        assertThatThrownBy(()->controller.chat(body(),request(owner,second))).isInstanceOf(GatewayException.class).satisfies(e->assertThat(((GatewayException)e).getCode()).isEqualTo("budget_exhausted"));
        verify(upstream,times(1)).chatCompletions(any());
        var payload=json.readTree("{\"resourceSpans\":[{\"resource\":{\"attributes\":[{\"key\":\"eap.agent.id\",\"value\":{\"stringValue\":\""+agent+"\"}}]},\"scopeSpans\":[{\"spans\":[{\"traceId\":\""+first+"\",\"spanId\":\"2222222222222222\",\"name\":\"agent.task\"}]}]}]}");
        controller.ingest(payload,request(owner,first));assertThat(connections.status(owner.instanceKey).traceAt()).isNotNull();
        var access=connections.authenticate(owner.instanceKey,RuntimeConnectionService.credentialToken(config,owner,lease.generation,lease.credentialRevision));
        var normalized=RuntimeScopedTelemetry.normalize(payload,access,raw->connections.resolveTrace(access,raw));
        assertThat(normalized.traces()).containsExactly(first);assertThat(normalized.payload().at("/resourceSpans/0/scopeSpans/0/spans/0/spanId").asText()).isNotEqualTo("2222222222222222");
        String unissued=UUID.randomUUID().toString().replace("-","");assertThat(connections.resolveTrace(access,unissued)).isEqualTo(RuntimeScopedTelemetry.trace(owner.instanceKey,unissued));
    }
    @Test void anotherInstanceCannotBorrowIssuedExperimentTraceEvenForSameAgent(){
        assertThatThrownBy(()->controller.chat(body(),request(other,first))).isInstanceOf(UnauthorizedException.class);verify(upstream,never()).chatCompletions(any());
        assertThat(db.queryForObject("select count(*) from budget_reservations where agent_id=?",Long.class,agent)).isZero();
    }
    @Test void replacementUidCannotTakeOverAnOldTrial(){
        owner.platformActorUid=UUID.randomUUID().toString();owner=runtimes.saveAndFlush(owner);nativeActors.put(owner.instanceKey,owner);
        assertThatThrownBy(()->controller.chat(body(),request(owner,first))).isInstanceOf(UnauthorizedException.class);verify(upstream,never()).chatCompletions(any());
    }
    @Test void completedTrialCannotFallBackToOrdinaryModelAdmission(){
        db.update("update agent_experiments set status='COMPLETED' where id=?",experiment);db.update("update agent_experiment_trials set status='SUCCEEDED' where experiment_id=?",experiment);
        assertThatThrownBy(()->controller.chat(body(),request(owner,first))).isInstanceOf(GatewayException.class).satisfies(e->assertThat(((GatewayException)e).getCode()).isEqualTo("experiment_not_running"));verify(upstream,never()).chatCompletions(any());
    }
}
