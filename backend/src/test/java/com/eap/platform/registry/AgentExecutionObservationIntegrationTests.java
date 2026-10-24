package com.eap.platform.registry;

import com.eap.platform.agent.*;
import com.eap.platform.common.*;
import com.eap.platform.substrate.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;

@SpringBootTest(properties={"spring.flyway.enabled=true","eap.substrate.enabled=true","eap.substrate.deploy-worker-enabled=false",
        "eap.runtime.integration.signing-key=synthetic-execution-test-signing-material",
        "eap.security.mode=JWT","eap.security.issuer-uri=https://idp.invalid.example/realm/eap","eap.security.jwk-set-uri=https://idp.invalid.example/jwks"})
@ActiveProfiles("test")
@AutoConfigureMockMvc
class AgentExecutionObservationIntegrationTests {
    @Autowired AgentExecutionObservationController controller;
    @Autowired AgentExecutionObservationService service;
    @Autowired RuntimeConnectionService connections;
    @Autowired RuntimeConnectionProperties config;
    @Autowired RuntimeConnectionRepository leases;
    @Autowired AgentRuntimeInstanceRepository runtimes;
    @Autowired AgentService agents;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate db;
    @Autowired MockMvc mvc;
    @MockitoBean SubstrateClient substrate;
    AgentRuntimeInstance owner,other;long agent;
    String producer,run,child,tool,trace;
    final Map<String,AgentRuntimeInstance> nativeActors=new ConcurrentHashMap<>();
    static final String PATH="/api/runtime-access/v1/execution-events";
    @BeforeEach void setup() {
        agent=agents.create(new AgentDtos.AgentRequest("EXEC-"+UUID.randomUUID(),"Execution fixture","","Test","Owner","Test",RiskLevel.LOW)).id();
        agents.updateStatus(agent,AgentLifecycleStatus.ACTIVE);owner=runtime();other=runtime();
        when(substrate.inspect(any())).thenAnswer(i->{var ref=i.getArgument(0,SubstrateActorRef.class);var r=nativeActors.get(ref.routingValue());return new SubstrateClient.ActorInventory(ref.atespace(),ref.name(),r.getBoundActorUid(),r.getBoundActorTemplate(),"RUNNING");});
        producer=UUID.randomUUID().toString();run=UUID.randomUUID().toString();child=UUID.randomUUID().toString();tool=UUID.randomUUID().toString();trace=UUID.randomUUID().toString().replace("-","");
    }
    AgentRuntimeInstance runtime() {
        var r=new AgentRuntimeInstance();r.agentId=agent;r.instanceKey="eap-agents/execution-"+UUID.randomUUID().toString().replace("-","");r.endpoint="substrate://"+r.instanceKey;r.protocol="SUBSTRATE";r.platformActorUid=UUID.randomUUID().toString();r.platformActorTemplate="eap-agents/execution-template";
        r=runtimes.saveAndFlush(r);nativeActors.put(r.instanceKey,r);
        var c=new RuntimeConnection();c.instanceKey=r.instanceKey;c.generation=UUID.randomUUID().toString();c.expiresAt=Instant.now().plusSeconds(900);c.verifiedAt=Instant.now();leases.saveAndFlush(c);return r;
    }
    String token(AgentRuntimeInstance r) {var c=leases.findByInstanceKey(r.instanceKey).orElseThrow();return RuntimeConnectionService.credentialToken(config,r,c.generation,c.credentialRevision);}
    MockHttpServletRequest request(AgentRuntimeInstance r) {var q=new MockHttpServletRequest("POST",PATH);q.addHeader("X-EAP-Runtime-Key",r.instanceKey);q.addHeader("X-EAP-Runtime-Token",token(r));return q;}
    ObjectNode event(String kind,String id,int sequence) {
        var e=json.createObjectNode().put("eventId",UUID.randomUUID().toString()).put("sequence",sequence).put("kind",kind).put("runId",id).put("traceId",trace).put("occurredAt","2026-10-01T00:00:00Z").put("processId",123);
        if(kind.endsWith("STARTED"))e.put("definitionKey","fixture");if(kind.endsWith("FINISHED"))e.put("outcome","SUCCEEDED");if(kind.startsWith("TOOL_"))e.put("toolCallId",tool);return e;
    }
    ObjectNode body(JsonNode... events) {var b=json.createObjectNode().put("schemaVersion",1).put("producerId",producer).put("framework","eap-native").put("frameworkVersion","0.1.0");var a=b.putArray("events");for(var e:events)a.add(e);return b;}
    AgentExecutionObservationService.Receipt ingest(JsonNode... events) {return controller.ingest(body(events),request(owner)).data();}
    long count(){return db.queryForObject("select count(*) from agent_execution_events where agent_id=?",Long.class,agent);}

    @Test void recordsLogicalParentAndTemporaryCodeWithoutSourceContent() {
        var start=event("RUN_STARTED",run,1);var nested=event("RUN_STARTED",child,2).put("parentRunId",run);
        var artifact=event("ARTIFACT_REGISTERED",child,3).put("artifactDigest","a".repeat(64)).put("sizeBytes",20).put("language","PYTHON");
        var call=event("TOOL_STARTED",child,4).put("artifactDigest","a".repeat(64));
        var ack=ingest(start,nested,artifact,call,event("TOOL_FINISHED",child,5),event("RUN_FINISHED",child,6),event("RUN_FINISHED",run,7));
        assertThat(ack.recorded()).isEqualTo(7);var graph=service.graph(ack.streamKey());assertThat(graph.complete()).isTrue();
        assertThat(graph.runs()).hasSize(2);assertThat(graph.runs().get(1).parentRunId()).isEqualTo(run);assertThat(graph.tools()).hasSize(1);assertThat(graph.artifacts()).hasSize(1);
        assertThat(service.events(agent,ack.streamKey(),0,50).items()).allSatisfy(e->{assertThat(e.runtimeInstanceKey()).isEqualTo(owner.instanceKey);assertThat(e.event().path("traceId").asText()).isNotEqualTo(trace);assertThat(e.event().has("code")).isFalse();});
    }
    @Test void duplicateDeliveryIsIdempotentAndChangedDuplicateIsRejected() {
        var start=event("RUN_STARTED",run,1);var first=ingest(start);var second=ingest(start);assertThat(second.duplicates()).isEqualTo(1);assertThat(second.streamKey()).isEqualTo(first.streamKey());
        start.put("definitionKey","changed");assertThatThrownBy(()->ingest(start)).isInstanceOf(ConflictException.class);assertThat(count()).isEqualTo(1);
    }
    @Test void rejectsSequenceAndLifecycleSlotReuse() {
        ingest(event("RUN_STARTED",run,1));assertThatThrownBy(()->ingest(event("RUN_STARTED",child,1))).isInstanceOf(ConflictException.class);
        assertThatThrownBy(()->ingest(event("RUN_STARTED",run,2))).isInstanceOf(ConflictException.class);assertThat(count()).isEqualTo(1);
    }
    @Test void outOfOrderDeliveryRemainsIncompleteUntilStartArrives() {
        var finish=event("RUN_FINISHED",run,2);var ack=ingest(finish);var pending=service.graph(ack.streamKey());assertThat(pending.complete()).isFalse();assertThat(pending.gaps()).contains("MISSING_SEQUENCE","INCOMPLETE_RUN");
        ingest(event("RUN_STARTED",run,1));assertThat(service.graph(ack.streamKey()).complete()).isTrue();
    }
    @Test void missingParentNeverBorrowsAnotherInstanceIdentity() {
        var first=ingest(event("RUN_STARTED",run,1),event("RUN_FINISHED",run,2));
        var ack=controller.ingest(body(event("RUN_STARTED",child,1).put("parentRunId",run),event("RUN_FINISHED",child,2)),request(other)).data();
        assertThat(ack.streamKey()).isNotEqualTo(first.streamKey());assertThat(service.graph(ack.streamKey()).gaps()).contains("MISSING_PARENT");assertThat(service.graph(ack.streamKey()).runs()).hasSize(1);
    }
    @Test void rejectsParentCyclesAndRollsBackWholeBatch() {
        ingest(event("RUN_STARTED",child,1).put("parentRunId",run));
        assertThatThrownBy(()->ingest(event("RUN_STARTED",run,2).put("parentRunId",child),event("RUN_FINISHED",child,3))).isInstanceOf(ConflictException.class);assertThat(count()).isEqualTo(1);
    }
    @Test void rejectsTraceOrToolOwnershipChanges() {
        ingest(event("RUN_STARTED",run,1),event("TOOL_STARTED",run,2));
        assertThatThrownBy(()->ingest(event("TOOL_FINISHED",child,3))).isInstanceOf(ConflictException.class);
        assertThatThrownBy(()->ingest(event("RUN_FINISHED",run,3).put("traceId","a".repeat(32)))).isInstanceOf(ConflictException.class);assertThat(count()).isEqualTo(2);
    }
    @Test void rejectsUnboundedOrPrivatePayloadWithoutPartialWrites() {
        var bad=event("RUN_STARTED",child,2).put("code","PRIVATE_SOURCE_MARKER");assertThatThrownBy(()->ingest(event("RUN_STARTED",run,1),bad)).isInstanceOf(BusinessException.class);assertThat(count()).isZero();
        var b=body(event("RUN_STARTED",run,1));b.put("agentId",999);assertThatThrownBy(()->controller.ingest(b,request(owner))).isInstanceOf(BusinessException.class);
        var a=json.createArrayNode();for(int i=1;i<=65;i++)a.add(event("RUN_STARTED",UUID.randomUUID().toString(),i));b.remove("agentId");b.set("events",a);assertThatThrownBy(()->controller.ingest(b,request(owner))).isInstanceOf(BusinessException.class);assertThat(count()).isZero();
    }
    @Test void cancellationAndMissingToolEndDoNotImplySuccess() {
        var ack=ingest(event("RUN_STARTED",run,1),event("TOOL_STARTED",run,2),event("RUN_FINISHED",run,3).put("outcome","CANCELLED"));
        var g=service.graph(ack.streamKey());assertThat(g.runs().getFirst().state()).isEqualTo("CANCELLED");assertThat(g.complete()).isFalse();assertThat(g.tools().getFirst().path("state").asText()).isEqualTo("INCOMPLETE");
    }
    @Test void changedNativeIdentityAndRevokedCredentialsCannotAppend() {
        var req=request(owner);var payload=body(event("RUN_STARTED",run,1));
        doReturn(new SubstrateClient.ActorInventory("eap-agents","wrong",UUID.randomUUID().toString(),"wrong","RUNNING")).when(substrate).inspect(any());
        assertThatThrownBy(()->controller.ingest(payload,req)).isInstanceOf(com.eap.platform.config.UnauthorizedException.class);assertThat(count()).isZero();
    }
    @Test void staleAccessIsRecheckedUnderTheWriteLock() {
        var access=connections.authenticate(owner.instanceKey,token(owner));var c=leases.findByInstanceKey(owner.instanceKey).orElseThrow();c.expiresAt=Instant.now().minusSeconds(1);leases.saveAndFlush(c);
        assertThatThrownBy(()->service.ingest(access,body(event("RUN_STARTED",run,1)))).isInstanceOf(com.eap.platform.config.UnauthorizedException.class);assertThat(count()).isZero();
    }
    @Test void identityGenerationChangeCannotReattributeAnExistingProducer() {
        var start=event("RUN_STARTED",run,1);ingest(start);
        var c=leases.findByInstanceKey(owner.instanceKey).orElseThrow();c.generation=UUID.randomUUID().toString();leases.saveAndFlush(c);
        assertThatThrownBy(()->ingest(start)).isInstanceOf(ConflictException.class);assertThat(count()).isEqualTo(1);
    }
    @Test void concurrentDuplicateRequestsSaveOneEvent() throws Exception {
        var payload=body(event("RUN_STARTED",run,1));var ready=new CountDownLatch(2);var go=new CountDownLatch(1);var pool=Executors.newFixedThreadPool(2);
        try {var futures=new ArrayList<Future<AgentExecutionObservationService.Receipt>>();for(int i=0;i<2;i++)futures.add(pool.submit(()->{ready.countDown();go.await();return controller.ingest(payload,request(owner)).data();}));assertThat(ready.await(10,TimeUnit.SECONDS)).isTrue();go.countDown();int saved=0,duplicates=0;for(var future:futures){var r=future.get(45,TimeUnit.SECONDS);saved+=r.recorded();duplicates+=r.duplicates();}assertThat(saved).isEqualTo(1);assertThat(duplicates).isEqualTo(1);assertThat(count()).isEqualTo(1);}finally {go.countDown();pool.shutdownNow();}
    }
    @Test void readPaginationAndRoleBoundaryAndMachineAuthentication() throws Exception {
        var ack=ingest(event("RUN_STARTED",run,1),event("RUN_FINISHED",run,2));var p=service.events(agent,ack.streamKey(),0,1);assertThat(p.hasMore()).isTrue();assertThat(service.events(agent,ack.streamKey(),p.nextAfterId(),1).hasMore()).isFalse();
        mvc.perform(get("/api/observability/agent-executions")).andExpect(status().isUnauthorized());
        for(String role:List.of("VIEWER","OPERATOR","GOVERNANCE_ADMIN","PLATFORM_ADMIN")) mvc.perform(get("/api/observability/agent-executions/"+ack.streamKey()+"/graph").with(jwt().authorities(new SimpleGrantedAuthority("ROLE_"+role)))).andExpect(status().isOk()).andExpect(jsonPath("$.data.complete").value(true));
        mvc.perform(post(PATH).contentType("application/json").content(body(event("RUN_STARTED",child,3)).toString())).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/observability/agent-executions").contentType("application/json").content("{}").with(jwt().authorities(new SimpleGrantedAuthority("ROLE_PLATFORM_ADMIN")))).andExpect(status().isForbidden());
        mvc.perform(post(PATH).header("X-EAP-Runtime-Key",owner.instanceKey).header("X-EAP-Runtime-Token",token(owner)).header("X-EAP-Agent-Id","999").contentType("application/json").content(body(event("RUN_STARTED",child,3)).toString())).andExpect(status().isBadRequest());
    }
    @Test void pythonSdkFixtureUsesTheSameContract() throws Exception {
        try(var in=getClass().getResourceAsStream("/execution-observer-fixture.json")) {
            var fixture=json.readTree(in);((ObjectNode)fixture).put("producerId",producer);var ack=controller.ingest(fixture,request(owner)).data();var graph=service.graph(ack.streamKey());
            assertThat(graph.complete()).isTrue();assertThat(graph.runs()).hasSize(3);assertThat(graph.tools()).hasSize(2);assertThat(graph.artifacts()).hasSize(1);
        }
    }
    @Test void realLangGraphFixtureRetainsExecutionAndToolRelationships() throws Exception {
        assertFrameworkFixture("langgraph", "1.2.17", 3);
    }
    @Test void realAutoGenFixtureRetainsNestedRpcRelationships() throws Exception {
        assertFrameworkFixture("autogen-core", "0.7.5", 5);
    }
    private void assertFrameworkFixture(String framework, String version, int runs) throws Exception {
        try(var in=getClass().getResourceAsStream("/execution-frameworks/"+framework+".json")) {
            assertThat(in).isNotNull();var fixture=(ObjectNode)json.readTree(in);
            assertThat(fixture.path("framework").asText()).isEqualTo(framework);
            assertThat(fixture.path("frameworkVersion").asText()).isEqualTo(version);
            fixture.put("producerId",producer);
            var ack=controller.ingest(fixture,request(owner)).data();var graph=service.graph(ack.streamKey());
            assertThat(graph.complete()).isTrue();assertThat(graph.gaps()).isEmpty();
            assertThat(graph.runs()).hasSize(runs);assertThat(graph.tools()).hasSize(2);
            var roots=graph.runs().stream().filter(r->r.parentRunId()==null).toList();assertThat(roots).hasSize(1);
            var runIds=graph.runs().stream().map(AgentExecutionObservationService.Run::runId).toList();
            assertThat(graph.runs()).allSatisfy(r->{assertThat(r.state()).isEqualTo("SUCCEEDED");if(r.parentRunId()!=null)assertThat(runIds).contains(r.parentRunId());});
            assertThat(graph.tools()).allSatisfy(t->{assertThat(t.path("state").asText()).isEqualTo("SUCCEEDED");assertThat(runIds).contains(t.path("runId").asText());});
            assertThat(ack.recorded()).isEqualTo(fixture.path("events").size());
            var duplicate=controller.ingest(fixture,request(owner)).data();assertThat(duplicate.recorded()).isZero();
            assertThat(duplicate.duplicates()).isEqualTo(ack.recorded());assertThat(count()).isEqualTo(ack.recorded());
            assertThat(service.events(agent,ack.streamKey(),0,50).items()).allSatisfy(e->{assertThat(e.agentId()).isEqualTo(agent);assertThat(e.runtimeInstanceKey()).isEqualTo(owner.instanceKey);assertThat(e.event().toString()).doesNotContain("PRIVATE_");});
            if(framework.equals("autogen-core")) {
                var direct=graph.runs().stream().filter(r->roots.getFirst().runId().equals(r.parentRunId())).map(AgentExecutionObservationService.Run::runId).toList();
                assertThat(direct).hasSize(2);assertThat(graph.runs().stream().filter(r->direct.contains(r.parentRunId())).count()).isEqualTo(2);
            }
        }
    }
    protected String migrationJdbcUrl() {return "jdbc:h2:mem:execution_upgrade_"+UUID.randomUUID()+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";}
    @Test void v91UpgradePreservesExistingWorkItemAndCanBeRepeated() throws Exception {
        String url=migrationJdbcUrl(),user=url.startsWith("jdbc:h2:")?"sa":"root";
        org.flywaydb.core.Flyway.configure().dataSource(url,user,"").locations("classpath:db/migration").target("90").load().migrate();
        try(var c=java.sql.DriverManager.getConnection(url,user,"");var s=c.createStatement()) {
            s.executeUpdate("insert into agent_work_items(item_key,item_type,title,severity,status,source_type,source_id,assignee,assigned_team,evidence_basis,evidence_json,occurrence_count,first_detected_at,last_detected_at,resolution,resolution_evidence_json,created_at,updated_at,row_version) values('execution-upgrade-original','GENERAL','Original','P1','WAITING','TEST','original','owner','team','HUMAN_CONFIRMED','{}',3,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,'note','{}',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,7)");
        }
        var flyway=org.flywaydb.core.Flyway.configure().dataSource(url,user,"").locations("classpath:db/migration").load();
        assertThat(flyway.migrate().migrationsExecuted).isEqualTo(1);assertThat(flyway.migrate().migrationsExecuted).isZero();
        try(var c=java.sql.DriverManager.getConnection(url,user,"");var s=c.createStatement()) {
            try(var r=s.executeQuery("select status,assignee,row_version,resolution from agent_work_items where item_key='execution-upgrade-original'")){assertThat(r.next()).isTrue();assertThat(r.getString(1)).isEqualTo("WAITING");assertThat(r.getString(2)).isEqualTo("owner");assertThat(r.getLong(3)).isEqualTo(7);assertThat(r.getString(4)).isEqualTo("note");}
            try(var r=s.executeQuery("select count(*) from agent_execution_events")){r.next();assertThat(r.getLong(1)).isZero();}
        }
    }
}
