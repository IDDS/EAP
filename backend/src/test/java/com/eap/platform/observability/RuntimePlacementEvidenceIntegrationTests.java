package com.eap.platform.observability;

import com.eap.platform.agent.*;
import com.eap.platform.deployment.RuntimePlacementService;
import com.eap.platform.registry.*;
import com.eap.platform.substrate.SubstrateClient;
import com.eap.platform.workflow.WorkflowTaskClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import java.net.InetSocketAddress;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"spring.flyway.enabled=true","eap.observability.runtime-denominators-enabled=true",
        "eap.workflow.execution.enabled=true","eap.workflow.execution.worker-enabled=false",
        "eap.workflow.execution.token=fixture-dispatch","eap.workflow.execution.allowed-hosts=localhost","eap.substrate.enabled=true","eap.substrate.deploy-worker-enabled=false",
        "eap.security.mode=JWT","eap.security.issuer-uri=https://idp.invalid.example/realm/eap","eap.security.jwk-set-uri=https://idp.invalid.example/jwks"})
@ActiveProfiles("test") @AutoConfigureMockMvc
class RuntimePlacementEvidenceIntegrationTests {
    static final String UID="00000000-0000-4000-8000-000000000001";
    static final ObjectMapper JSON = new ObjectMapper();
    static final AtomicInteger sends = new AtomicInteger();
    static volatile boolean broken;
    static final HttpServer HTTP = server();
    static HttpServer server() {
        try {
            var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
            server.createContext("/",exchange -> {
                sends.incrementAndGet(); var input=JSON.readTree(exchange.getRequestBody());
                if(broken){exchange.close();return;}
                byte[] raw=JSON.writeValueAsBytes(Map.of("taskKey",input.path("taskKey").asText(),"traceId",input.path("traceId").asText(),"status","SUCCEEDED"));
                exchange.sendResponseHeaders(200,raw.length);try(var out=exchange.getResponseBody()){out.write(raw);}
            });server.start();return server;
        } catch(Exception e){throw new IllegalStateException(e);}
    }
    @DynamicPropertySource static void router(DynamicPropertyRegistry p){p.add("eap.substrate.router-url",()->"http://127.0.0.1:"+HTTP.getAddress().getPort());}
    @AfterAll static void stop(){HTTP.stop(0);}
    @Autowired RuntimePlacementEvidenceStore store;
    @Autowired RuntimePlacementEvidenceService sampler;
    @Autowired ObservationCoverageService coverage;
    @Autowired WorkflowTaskClient client;
    @Autowired JdbcTemplate db;
    @Autowired AgentRepository agents;
    @Autowired AgentRuntimeInstanceRepository runtimes;
    @Autowired MockMvc mvc;
    @MockitoBean RuntimePlacementService positions;
    @MockitoBean RuntimeConnectionService connections;
    @MockitoBean SubstrateClient substrate;
    AgentRuntimeInstance runtime;
    String key,rawTrace;
    long agent,operation;
    @BeforeEach void setup(){
        var a=new Agent();a.setCode("PE-"+UUID.randomUUID());a.setName("Placement fixture");a.setOwnerDepartment("test");a.setOwnerUser("test");a.setCategory("test");a.setLifecycleStatus(AgentLifecycleStatus.ACTIVE);a.setRiskLevel(RiskLevel.LOW);agent=agents.save(a).getId();
        String instance="eap-agents/p-"+UUID.randomUUID();
        db.update("insert into agent_runtime_instances(agent_id,instance_key,endpoint,protocol,dispatch_authenticated,status,load_score,last_heartbeat_at,created_at,updated_at,platform_actor_uid,platform_actor_template) values(?,?,?,'SUBSTRATE',true,'ONLINE',0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,?,'eap-agents/template')",agent,instance,"substrate://"+instance,UID);
        runtime=runtimes.findByInstanceKey(instance).orElseThrow();key="placement-"+UUID.randomUUID();rawTrace=UUID.randomUUID().toString().replace("-","");
        db.update("insert into observation_operations(entry_kind,operation_key,agent_id,raw_trace_id,registration_state,expected_agent_spans,expected_model_events,span_name,created_at) values('RUNTIME_TASK',?,?,?,'PENDING_IDENTITY',1,0,'agent.task',CURRENT_TIMESTAMP)",key,agent,rawTrace);
        operation=db.queryForObject("select id from observation_operations where operation_key=?",Long.class,key);
        when(connections.executable(any())).thenReturn(true);when(connections.dispatchToken(any())).thenReturn("fixture-dispatch");
        when(positions.currentForBinding(any(),any(),any(),any())).thenReturn(snapshot());
        sends.set(0);broken=false;
    }
    RuntimePlacementService.Snapshot snapshot(){
        return new RuntimePlacementService.Snapshot(runtime.getInstanceKey(),"ASSIGNED","VERIFIED_CONTROL_SNAPSHOT","SUBSTRATE_CONTROL_API",Instant.now(),Instant.now(),UID,11L,"RUNNING",
                new SubstrateClient.WorkerPlacement("worker",UID,"eap-agents","pool","pod",UID,"node-original"));
    }
    RuntimePlacementEvidenceStore.Request request(){return new RuntimePlacementEvidenceStore.Request(key,rawTrace,agent,runtime.getId(),runtime.getInstanceKey(),runtime.getEndpoint(),UID,"eap-agents/template",RuntimePlacementEvidenceStore.Phase.DISPATCH_REQUESTED,Instant.now());}
    void dispatched(){assertThat(coverage.runtimeDispatch(key,runtime,Instant.now().plusSeconds(30))).isEqualTo(rawTrace);}
    void awaitRecorded()throws Exception{
        for(int n=0;n<100;n++){if(store.recorded(operation).size()==2 && store.recorded(operation).stream().allMatch(x->x.snapshot()!=null))return;Thread.sleep(30);}
        fail("Both samples must complete");
    }
    @Test void originalBindingAndTraceRequiredAndDuplicateDoesNotReplaceSavedLocation(){
        assertThat(store.claim(request())).isNull();dispatched();
        var original=request();
        assertThat(store.claim(new RuntimePlacementEvidenceStore.Request(key,"wrong",agent,runtime.getId(),runtime.getInstanceKey(),runtime.getEndpoint(),UID,"eap-agents/template",original.phase(),original.requestedAt()))).isNull();
        assertThat(store.claim(new RuntimePlacementEvidenceStore.Request(key,rawTrace,agent,runtime.getId(),runtime.getInstanceKey(),runtime.getEndpoint(),"replacement",original.template(),original.phase(),original.requestedAt()))).isNull();
        var claim=store.claim(original);assertThat(claim).isNotNull();
        assertThat(store.recorded(operation).getFirst().captureState()).isEqualTo("INCOMPLETE");
        var value=snapshot();assertThat(store.complete(claim,value)).isTrue();assertThat(store.claim(original)).isNull();
        assertThat(store.complete(claim,new RuntimePlacementService.Snapshot(runtime.getInstanceKey(),"UNKNOWN","LATER",null,Instant.now(),Instant.now(),null,null,null,null))).isFalse();
        assertThat(store.recorded(operation).getFirst().snapshot()).isEqualTo(value);
    }
    @Test void concurrentDuplicateClaimsHaveOneWinner()throws Exception{
        dispatched();var request=request();
        try(var pool=Executors.newFixedThreadPool(6)){
            var calls=new ArrayList<Callable<RuntimePlacementEvidenceStore.Claim>>();for(int n=0;n<12;n++)calls.add(()->store.claim(request));
            int winners=0;for(var future:pool.invokeAll(calls))if(future.get()!=null)winners++;
            assertThat(winners).isEqualTo(1);assertThat(store.recorded(operation)).hasSize(1);
        }
    }
    @Test void slowPositionReadsDoNotDelayHttpReceiptOrCreateAnotherDispatch()throws Exception{
        var entered=new CountDownLatch(2);var release=new CountDownLatch(1);
        when(positions.currentForBinding(any(),any(),any(),any())).thenAnswer(call->{entered.countDown();release.await(10,TimeUnit.SECONDS);return snapshot();});
        try {
            var result=CompletableFuture.supplyAsync(()->client.invoke(runtime,Map.of("taskKey",key),Instant.now().plusSeconds(20)));
            assertThat(result.get(3,TimeUnit.SECONDS).status()).isEqualTo("SUCCEEDED");
            assertThat(entered.await(3,TimeUnit.SECONDS)).isTrue();assertThat(sends.get()).isEqualTo(1);
        } finally {release.countDown();}
        awaitRecorded();assertThatThrownBy(()->client.invoke(runtime,Map.of("taskKey",key),Instant.now().plusSeconds(20))).isInstanceOf(RuntimeException.class);
        assertThat(sends.get()).isEqualTo(1);
    }
    @Test void captureFailurePreservesUnknownTaskAndDoesNotRetry()throws Exception{
        broken=true;when(positions.currentForBinding(any(),any(),any(),any())).thenThrow(new IllegalStateException("private failure"));
        assertThat(client.invoke(runtime,Map.of("taskKey",key),Instant.now().plusSeconds(20)).status()).isEqualTo("EXECUTION_UNKNOWN");
        awaitRecorded();assertThat(sends.get()).isEqualTo(1);
        assertThat(store.recorded(operation)).allSatisfy(item->{assertThat(item.snapshot().reason()).isEqualTo("CAPTURE_FAILED");assertThat(item.toString()).doesNotContain("private failure");});
        assertThat(coverage.detail(operation).operation().executionStatus()).isEqualTo("EXECUTION_UNKNOWN");
    }
    @Test void readsRetainOriginalSnapshotWithoutProbingCurrentOrBackfillingHistory(){
        dispatched();var claim=store.claim(request());var value=snapshot();store.complete(claim,value);
        reset(positions);
        db.update("update agent_runtime_instances set platform_actor_uid='replacement',status='OFFLINE' where id=?",runtime.getId());
        assertThat(coverage.detail(operation).placements().getFirst().snapshot()).isEqualTo(value);
        verifyNoInteractions(positions);
        assertThat(store.recorded(operation+100000)).isEmpty();
    }
    @ParameterizedTest @ValueSource(strings={"VIEWER","OPERATOR","GOVERNANCE_ADMIN","PLATFORM_ADMIN"})
    void metadataReadUsesExistingPermissionAndNeverCaptures(String role)throws Exception{
        dispatched();store.complete(store.claim(request()),snapshot());reset(positions);
        var auth=jwt().jwt(j->j.subject("fixture-"+role)).authorities(new SimpleGrantedAuthority("ROLE_"+role));
        mvc.perform(get("/api/observability/operations/"+operation).with(auth)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.placements[0].snapshot.placement.nodeName").value("node-original"));
        mvc.perform(get("/api/observability/operations/"+operation)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/observability/operations/"+operation).with(jwt().authorities(new SimpleGrantedAuthority("ROLE_DATA_OWNER")))).andExpect(status().isForbidden());
        verifyNoInteractions(positions);
    }
}
