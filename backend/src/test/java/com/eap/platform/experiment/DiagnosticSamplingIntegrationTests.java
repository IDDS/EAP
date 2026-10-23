package com.eap.platform.experiment;

import com.eap.platform.agent.*;
import com.eap.platform.common.ConflictException;
import com.eap.platform.observability.*;
import com.eap.platform.registry.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"spring.flyway.enabled=true","eap.security.mode=JWT",
        "eap.security.issuer-uri=https://idp.invalid.example/realm/eap","eap.security.jwk-set-uri=https://idp.invalid.example/jwks",
        "eap.experiment.enabled=true","eap.experiment.worker-initial-delay-ms=3600000","eap.substrate.deploy-worker-enabled=false"})
@ActiveProfiles("test") @AutoConfigureMockMvc
class DiagnosticSamplingIntegrationTests {
    @Autowired AgentService agents;
    @Autowired AgentExperimentService experiments;
    @Autowired DiagnosticPolicyService policies;
    @Autowired DiagnosticSamplingService diagnostics;
    @Autowired OtlpTraceIngestionService ingestion;
    @Autowired JdbcTemplate db;
    @Autowired ObjectMapper json;
    @Autowired MockMvc mvc;
    @Autowired PlatformTransactionManager transactionManager;
    @MockitoBean AgentExperimentClient client;
    long agent, runtime;
    String instance, endpoint, template;
    AgentExperimentDtos.Submit request;
    AgentExperimentDtos.Summary experiment;

    @BeforeEach void setup() {
        agent=agents.create(new AgentDtos.AgentRequest("DIAG-"+UUID.randomUUID(),"Public diagnostic fixture","","Test","Owner","Test",RiskLevel.LOW)).id();
        agents.updateStatus(agent,AgentLifecycleStatus.ACTIVE);
        instance="eap-agents/diag-"+agent; endpoint="substrate://"+instance; template="eap-agents/diag-template-"+agent;
        db.update("insert into agent_runtime_instances(agent_id,instance_key,endpoint,protocol,dispatch_authenticated,status,load_score,platform_actor_uid,platform_actor_template,created_at,updated_at) values(?,?,?,'SUBSTRATE',false,'ONLINE',0,?,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",agent,instance,endpoint,"uid-"+agent,template);
        runtime=db.queryForObject("select id from agent_runtime_instances where instance_key=?",Long.class,instance);
        db.update("insert into substrate_agent_deployments(deployment_key,agent_id,version,source_type,status,config_json,image_ref,atespace,template_name,instance_key,steps_json,requested_by,created_at,updated_at) values(?,?,1,'IMAGE','READY','{}',?,'eap-agents',?,?,'[]','test',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)","diag-deploy-"+agent,agent,"registry.test/runner@sha256:"+"b".repeat(64),"diag-template-"+agent,instance);
        var profile=json.createObjectNode().put("agentId",Long.toString(agent)).put("systemPrompt","PRIVATE_BASELINE_MARKER").put("maxTokens",128).put("profileDigest","a".repeat(64));
        when(client.profile(endpoint)).thenReturn(new AgentExperimentClient.RuntimeProfile(profile,"uid-"+agent,template));
        request=new AgentExperimentDtos.Submit(agent,instance,"a".repeat(64),"PRIVATE_CANDIDATE_MARKER",128,
                List.of(new AgentExperimentDtos.Sample("PUBLIC_INPUT","PUBLIC_EXPECTED")),true,UUID.randomUUID().toString());
    }

    @AfterEach void cleanup() {
        db.update("delete from experiment_diagnostic_receipts where trial_id in (select id from agent_experiment_trials where experiment_id in (select id from agent_experiments where agent_id=?))",agent);
        db.update("delete from experiment_diagnostic_decisions where agent_id=?",agent);
        for(String table:List.of("agent_experiment_trials","agent_experiment_variants")) db.update("delete from "+table+" where experiment_id in (select id from agent_experiments where agent_id=?)",agent);
        db.update("delete from agent_experiments where agent_id=?",agent);
        db.update("delete from trace_span_observations where agent_id=?",agent);
    }
    void rate(int rate) { policies.publish(json.valueToTree(Map.of("expectedVersion",policies.current().version(),"sampleBasisPoints",rate)),"fixture-owner"); }
    void submit(int rate) { rate(rate); experiment=experiments.submit(request,"fixture-owner");clearInvocations(client); }
    RuntimeConnectionService.Access access() { return new RuntimeConnectionService.Access(instance,agent,"fixture-generation","runtime-"+runtime); }
    JsonNode payload(int count, String name) {
        var root=json.createObjectNode();var resource=root.putArray("resourceSpans").addObject();
        resource.putObject("resource").putObject("attributes").put("private.resource","PRIVATE_DIAGNOSTIC_MARKER");
        var spans=resource.putArray("scopeSpans").addObject().putArray("spans");
        for(int n=1;n<=count;n++) {
            var s=spans.addObject().put("name",name).put("traceId",experiment.trials().getFirst().traceId())
                    .put("spanId",String.format("%016x",n)).put("startTimeUnixNano","1780000000000000000").put("endTimeUnixNano","1780000000001000000");
            s.putObject("attributes").put("risk","LOW").put("approved",true).put("agent.input","PRIVATE_DIAGNOSTIC_MARKER");
            s.putObject("status").put("code",2).put("message","PRIVATE_DIAGNOSTIC_MARKER");
            s.putArray("events").addObject().put("name","PRIVATE_DIAGNOSTIC_MARKER");
        }
        return root;
    }
    DiagnosticSamplingService.TrialDecision first() { return diagnostics.evidence(experiment.id()).trials().getFirst(); }
    long count(String table) { return db.queryForObject("select count(*) from "+table,Long.class); }

    @Test void defaultPolicyPreservesAllOptionalDetailsUntilExplicitPublication() {
        var original=db.queryForMap("select algorithm,sample_basis_points from diagnostic_policy_versions where version=1");
        assertThat(original.get("algorithm")).isEqualTo("SHA256_TRACE_V1");assertThat(original.get("sample_basis_points")).isEqualTo(10000);
    }
    @Test void priorIntentKeepsOriginalVersionAndDeterministicChoiceAfterNewPublication() {
        submit(3711);var original=diagnostics.evidence(experiment.id());rate(10000);
        assertThat(experiments.submit(request,"fixture-owner").id()).isEqualTo(experiment.id());
        assertThat(diagnostics.evidence(experiment.id())).isEqualTo(original);verifyNoInteractions(client);
        var input="SHA256_TRACE_V1|"+first().policyVersion()+"|"+agent+"|"+runtime+"|"+instance+"|"+first().traceId();
        try {
            var h=java.security.MessageDigest.getInstance("SHA-256").digest(input.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            int bucket=new java.math.BigInteger(1,Arrays.copyOf(h,8)).mod(java.math.BigInteger.valueOf(10000)).intValue();
            assertThat(first().selected()).isEqualTo(bucket<3711);
        } catch(java.security.NoSuchAlgorithmException e) { throw new AssertionError(e); }
    }
    @Test void newRegistrationUsesLatestPolicyWhileOldVersionIsImmutable() {
        submit(0);long v=first().policyVersion();rate(10000);
        assertThat(db.queryForObject("select sample_basis_points from diagnostic_policy_versions where version=?",Integer.class,v)).isZero();
        assertThat(first().policyVersion()).isEqualTo(v);assertThat(first().selected()).isFalse();
    }
    @Test void coreAgentMetadataIsRetainedAtZeroRateAndDoesNotCreateDiagnosticReceipts() {
        submit(0);var body=payload(1,"agent.experiment");var filtered=diagnostics.filter(body,access());
        assertThat(filtered.payload()).isEqualTo(body);assertThat(filtered.warnings()).isEmpty();
        assertThat(count("experiment_diagnostic_receipts")).isZero();assertThat(first().receiptStatus()).isEqualTo("NOT_RECEIVED");
    }
    @Test void callerRiskStatusAndApprovalCannotOverrideSamplingOrRewriteExecution() {
        submit(0);var before=experiments.get(experiment.id());var result=diagnostics.filter(payload(1,"agent.diagnostic"),access());
        assertThat(result.warnings()).containsExactly("DIAGNOSTIC_POLICY_NOT_SELECTED");
        assertThat(first().filtered()).isEqualTo(1);assertThat(first().retained()).isZero();
        assertThat(experiments.get(experiment.id())).isEqualTo(before);verifyNoInteractions(client);
    }
    @ParameterizedTest @ValueSource(strings={"UNKNOWN","FAILED"})
    void trustedUnconfirmedExecutionRetainsOnlySafeMetadataAtZeroRate(String status) {
        submit(0);db.update("update agent_experiment_trials set status=? where id=?",status,experiment.trials().getFirst().id());
        var selected=diagnostics.filter(payload(1,"agent.diagnostic"),access());
        assertThat(selected.payload().toString()).contains("SERVER_EXECUTION_UNCONFIRMED").doesNotContain("PRIVATE_DIAGNOSTIC_MARKER","LOW","approved");
        ingestion.ingestScoped(selected.payload(),agent,access().endpointKey());
        assertThat(first().selected()).isFalse();assertThat(first().retained()).isEqualTo(1);assertThat(first().executionStatus()).isEqualTo(status);
        assertThat(db.queryForObject("select attributes_json from trace_span_observations where agent_id=?",String.class,agent)).doesNotContain("PRIVATE_DIAGNOSTIC_MARKER");
        assertThat(db.queryForObject("select count(*) from content_observations where agent_id=?",Long.class,agent)).isZero();
    }
    @Test void selectedButUnreceivedDiagnosticIsNotReportedAsComplete() {
        submit(10000);assertThat(first().selected()).isTrue();assertThat(first().receiptStatus()).isEqualTo("NOT_RECEIVED");assertThat(first().retained()).isZero();
    }
    @Test void duplicateEvidenceNeverOverwritesOriginalAndLimitsRemainExplicit() {
        submit(10000);var firstBatch=diagnostics.filter(payload(17,"agent.diagnostic"),access());ingestion.ingestScoped(firstBatch.payload(),agent,access().endpointKey());
        assertThat(firstBatch.warnings()).contains("DIAGNOSTIC_LIMIT_EXCEEDED");
        var replay=diagnostics.filter(payload(1,"agent.diagnostic"),access());assertThat(replay.warnings()).containsExactly("DIAGNOSTIC_DUPLICATE");
        assertThat(first().retained()).isEqualTo(16);assertThat(first().limitDropped()).isEqualTo(1);assertThat(first().duplicateAttempts()).isEqualTo(1);
        assertThat(db.queryForObject("select count(*) from trace_span_observations where agent_id=?",Long.class,agent)).isEqualTo(16);
    }
    @Test void missingRegistrationNeverFallsBackToCurrentPolicyOrInfersLowRisk() {
        submit(10000);db.update("delete from experiment_diagnostic_decisions where agent_id=?",agent);
        assertThat(first().policyVersion()).isNull();assertThat(first().selected()).isNull();assertThat(first().identityEvidence()).isEqualTo("UNREGISTERED");
        assertThat(diagnostics.filter(payload(1,"agent.diagnostic"),access()).warnings()).containsExactly("DIAGNOSTIC_IDENTITY_UNCONFIRMED");
        assertThat(count("experiment_diagnostic_receipts")).isZero();
    }
    @Test void unboundLegacyInstanceRegistersUnknownInsteadOfInventingTrustedIdentity() {
        db.update("update agent_runtime_instances set platform_actor_uid=null,platform_actor_template=null where id=?",runtime);submit(10000);
        assertThat(first().selected()).isNull();assertThat(first().identityEvidence()).isEqualTo("UNCONFIRMED");
    }
    @Test void foreignEndpointAgentUidOrTemplateCannotUseFrozenDecision() {
        submit(10000);
        for(var wrong:List.of(new RuntimeConnectionService.Access(instance,agent+1,"g",access().endpointKey()),new RuntimeConnectionService.Access(instance,agent,"g","runtime-foreign")))
            assertThat(diagnostics.filter(payload(1,"agent.diagnostic"),wrong).warnings()).containsExactly("DIAGNOSTIC_IDENTITY_UNCONFIRMED");
        db.update("update agent_runtime_instances set platform_actor_uid='new-uid' where id=?",runtime);
        assertThat(diagnostics.filter(payload(1,"agent.diagnostic"),access()).warnings()).containsExactly("DIAGNOSTIC_IDENTITY_UNCONFIRMED");
        assertThat(first().identityEvidence()).isEqualTo("UNCONFIRMED");assertThat(first().retained()).isZero();
    }
    @Test void rollbackDoesNotMarkEvidenceReceivedWhenIngestionFails() {
        submit(10000);var tx=new TransactionTemplate(transactionManager);
        assertThatThrownBy(()->tx.execute(s->{diagnostics.filter(payload(1,"agent.diagnostic"),access());throw new IllegalStateException("synthetic ingestion failure");})).isInstanceOf(IllegalStateException.class);
        assertThat(first().receiptStatus()).isEqualTo("NOT_RECEIVED");assertThat(count("experiment_diagnostic_receipts")).isZero();
    }
    @Test void simultaneousPublishersCannotOverwriteEachOther() throws Exception {
        long version=policies.current().version();var request=json.valueToTree(Map.of("expectedVersion",version,"sampleBasisPoints",10000));
        var pool=Executors.newFixedThreadPool(2);var start=new CountDownLatch(1);
        try {
            var work=(Callable<Boolean>)()->{start.await();try{policies.publish(request,"concurrent-fixture");return true;}catch(ConflictException e){return false;}};
            var a=pool.submit(work);var b=pool.submit(work);start.countDown();
            assertThat(List.of(a.get(20,TimeUnit.SECONDS),b.get(20,TimeUnit.SECONDS))).containsExactlyInAnyOrder(true,false);
            assertThat(policies.current().version()).isEqualTo(version+1);
        } finally { pool.shutdownNow(); }
    }
    @ParameterizedTest @ValueSource(strings={"VIEWER","OPERATOR","GOVERNANCE_ADMIN","PLATFORM_ADMIN"})
    void diagnosticEvidenceUsesConsoleCapabilityWithoutContentOrSideEffects(String role) throws Exception {
        submit(10000);var before=experiments.get(experiment.id());long models=count("model_invocation_events"),budgets=count("budget_reservations");
        var response=mvc.perform(get("/api/agent-experiments/"+experiment.id()+"/diagnostics").with(jwt().authorities(new SimpleGrantedAuthority("ROLE_"+role))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.registeredTrials").value(2)).andReturn().getResponse().getContentAsString();
        assertThat(response).doesNotContain("PRIVATE_BASELINE_MARKER","PRIVATE_CANDIDATE_MARKER","PUBLIC_INPUT","PUBLIC_EXPECTED","accessToken");
        assertThat(experiments.get(experiment.id())).isEqualTo(before);assertThat(count("model_invocation_events")).isEqualTo(models);assertThat(count("budget_reservations")).isEqualTo(budgets);verifyNoInteractions(client);
        mvc.perform(get("/api/agent-experiments/"+experiment.id()+"/diagnostics")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/agent-experiments/"+experiment.id()+"/diagnostics").with(jwt().authorities(new SimpleGrantedAuthority("ROLE_DATA_OWNER")))).andExpect(status().isForbidden());
    }
}
