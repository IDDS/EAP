package com.eap.platform.observability;

import com.eap.platform.agent.*;
import com.eap.platform.common.BusinessException;
import com.eap.platform.deployment.SubstrateDeploymentService;
import com.eap.platform.registry.*;
import com.eap.platform.substrate.SubstrateClient;
import com.eap.platform.workflow.*;
import com.fasterxml.jackson.databind.*;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.net.InetSocketAddress;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real Spring admission and HTTP clients; the loopback runtime is a fixture, never a model provider. */
@SpringBootTest(properties={"spring.flyway.enabled=true","eap.observability.runtime-denominators-enabled=true",
        "eap.workflow.execution.enabled=true","eap.workflow.execution.worker-enabled=false",
        "eap.workflow.execution.token=fixture-dispatch","eap.workflow.execution.allowed-hosts=localhost",
        "eap.substrate.enabled=true","eap.substrate.deploy-worker-enabled=false"})
@ActiveProfiles("test")
class RuntimeObservationIntegrationTests {
    static final ObjectMapper JSON = new ObjectMapper();
    static final AtomicInteger sends = new AtomicInteger();
    static volatile JsonNode lastEnvelope;
    static volatile String mode = "MATCH";
    static final HttpServer HTTP = server();
    static HttpServer server() {
        try {
            var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", exchange -> {
                if ("/eap/state".equals(exchange.getRequestURI().getPath())) {
                    byte[] body="{\"conversationMode\":\"EXPLICIT_V1\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(200,body.length);exchange.getResponseBody().write(body);exchange.close();return;
                }
                sends.incrementAndGet(); lastEnvelope = JSON.readTree(exchange.getRequestBody());
                if ("BROKEN".equals(mode)) { exchange.close(); return; }
                var result = JSON.createObjectNode().put("status", "SUCCEEDED").put("output", "public-result").put("reply", "public-reply").put("turns", 1);
                if (lastEnvelope.has("taskKey")) result.put("taskKey", lastEnvelope.path("taskKey").asText());
                if (!"LEGACY".equals(mode)) result.put("traceId", "MISMATCH".equals(mode) ? "b".repeat(32) : lastEnvelope.path("traceId").asText());
                byte[] bytes = JSON.writeValueAsBytes(result); exchange.sendResponseHeaders(200, bytes.length);
                try (var out = exchange.getResponseBody()) { out.write(bytes); }
            }); server.start(); return server;
        } catch (Exception e) { throw new IllegalStateException(e); }
    }
    @DynamicPropertySource static void router(DynamicPropertyRegistry properties) {
        properties.add("eap.substrate.router-url", () -> "http://127.0.0.1:" + HTTP.getAddress().getPort());
    }
    @AfterAll static void stop() { HTTP.stop(0); }
    @Autowired ObservationCoverageService coverage;
    @Autowired WorkflowService workflows;
    @Autowired WorkflowExecutionService executions;
    @Autowired WorkflowTaskClient client;
    @Autowired SubstrateDeploymentService deployments;
    @Autowired com.eap.platform.deployment.ChatSubmissionService chats;
    @Autowired AgentRepository agents;
    @Autowired AgentRuntimeInstanceRepository runtimes;
    @Autowired JdbcTemplate db;
    @Autowired OtlpTraceIngestionService ingestion;
    @Autowired PlatformTransactionManager manager;
    @MockitoBean RuntimeConnectionService connections;
    @MockitoBean SubstrateClient substrate;
    long agent;
    AgentRuntimeInstance runtime;
    @BeforeEach void setup() {
        db.update("delete from observation_operations"); sends.set(0); mode = "MATCH";
        var a = new Agent(); a.setCode("RO-" + UUID.randomUUID()); a.setName("Public fixture"); a.setOwnerDepartment("test"); a.setOwnerUser("test"); a.setCategory("test"); a.setLifecycleStatus(AgentLifecycleStatus.ACTIVE); a.setRiskLevel(RiskLevel.LOW); agent = agents.save(a).getId();
        String key = "eap-agents/public-" + UUID.randomUUID();
        db.update("insert into agent_runtime_instances(agent_id,instance_key,endpoint,protocol,dispatch_authenticated,status,load_score,last_heartbeat_at,created_at,updated_at,platform_actor_uid,platform_actor_template) values(?,?,?,'SUBSTRATE',true,'ONLINE',0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,'original-uid','original-template')", agent, key, "substrate://" + key);
        runtime = runtimes.findByInstanceKey(key).orElseThrow();
        when(connections.executable(any())).thenReturn(true); when(connections.dispatchToken(any())).thenReturn("fixture-dispatch");
    }
    WorkflowExecutionService.Response submit(String request) {
        return submit(request, "{}");
    }
    WorkflowExecutionService.Response submit(String request, String config) {
        var workflow = workflows.create(new WorkflowDtos.WorkflowRequest("ro-" + UUID.randomUUID(), "Public route", "test"));
        node(workflow.id(), "start", "START", null); node(workflow.id(), "one", "AGENT", agent); node(workflow.id(), "two", "ARTIFACT", agent); node(workflow.id(), "end", "END", null);
        for (var pair : List.of(List.of("start", "one"), List.of("one", "two"), List.of("two", "end"))) workflows.addEdge(workflow.id(), new WorkflowDtos.EdgeRequest(pair.get(0), pair.get(1), "always"));
        workflows.upsertNode(workflow.id(), new WorkflowDtos.NodeRequest("one", "one", "AGENT", agent, null, 0, 0, config));
        return executions.submit(workflow.id(), new WorkflowDtos.RunRequest("public input", Map.of(), request), "original-owner");
    }
    void node(long workflow, String key, String type, Long id) { workflows.upsertNode(workflow, new WorkflowDtos.NodeRequest(key, key, type, id, null, 0, 0, "{}")); }
    long operation(String key) { return db.queryForObject("select id from observation_operations where operation_key=?", Long.class, key); }
    ObservationCoverageService.Operation get(String key) { return coverage.detail(operation(key)).operation(); }
    WorkflowTaskClient.Receipt invoke(String key) { return client.invoke(runtime, Map.of("taskKey", key, "objective", "public input"), Instant.now().plusSeconds(20)); }
    void agentEvidence(String key) throws Exception {
        var o = db.queryForMap("select * from observation_operations where operation_key=?", key);
        ingestion.ingestScoped(JSON.readTree("{\"resourceSpans\":[{\"scopeSpans\":[{\"spans\":[{\"traceId\":\"" + o.get("trace_id") + "\",\"spanId\":\"1234567890abcdef\",\"name\":\"" + o.get("span_name") + "\",\"attributes\":{\"agent.runtime\":\"substrate\"},\"status\":{\"code\":1}}]}]}]}"), agent, "runtime-" + runtime.getId());
    }
    void evidence(String key, String source) throws Exception {
        agentEvidence(key);
        var o = db.queryForMap("select * from observation_operations where operation_key=?", key);
        db.update("insert into model_invocation_events(event_key,trace_id,agent_id,endpoint_key,observation_source,governance_covered,provider,model_name,status,input_tokens,output_tokens,cached_input_tokens,reasoning_tokens,latency_ms,money_cost,currency,tool_call_count,mcp_server_count,observed_at,created_at) values(?,?,?,?,?,true,'test','fixture-model','SUCCEEDED',1,1,0,0,1,0,'USD',0,0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)", UUID.randomUUID().toString(), o.get("trace_id"), agent, "runtime-" + runtime.getId(), source);
    }
    static final String NO_MODEL = "{\"eapObservation\":{\"version\":\"EAP_OBSERVATION_V1\",\"modelEvents\":\"NONE\"}}";
    @Test void declaredNoModelTaskStillRequiresMatchedIdentityReceiptAndAgentSpan() throws Exception {
        String key = submit("no-model", NO_MODEL).runKey() + ":1";
        assertThat(get(key).expectedModelEvents()).isZero();
        invoke(key); assertThat(get(key).coverage()).isEqualTo("MISSING_AGENT");
        agentEvidence(key); assertThat(get(key).coverage()).isEqualTo("COMPLETE");
        assertThat(get(key).modelEvents()).isZero(); assertThat(sends).hasValue(1);
        db.update("update agent_runtime_instances set platform_actor_uid='replacement' where id=?", runtime.getId());
        assertThat(get(key).coverage()).isEqualTo("IDENTITY_UNCONFIRMED");
    }
    @Test void unexpectedModelIsVisibleEvenWhenNoModelWasDeclared() throws Exception {
        String key = submit("unexpected-model", NO_MODEL).runKey() + ":1";
        invoke(key); evidence(key, "GATEWAY");
        assertThat(get(key).coverage()).isEqualTo("UNEXPECTED_MODEL");
        assertThat(get(key).modelEvents()).isEqualTo(1); assertThat(get(key).executionStatus()).isEqualTo("SUCCEEDED");
    }
    @Test void defaultStillDetectsMissingModelAndLaterEditsCannotRewriteOriginalExpectations() throws Exception {
        var run = submit("frozen-model"); String key = run.runKey() + ":1";
        invoke(key); agentEvidence(key); assertThat(get(key).coverage()).isEqualTo("MISSING_MODEL");
        workflows.upsertNode(run.workflowId(), new WorkflowDtos.NodeRequest("one", "one", "AGENT", agent, null, 0, 0, NO_MODEL));
        assertThat(executions.submit(run.workflowId(), new WorkflowDtos.RunRequest("public input", Map.of(), "frozen-model"), "original-owner").runKey()).isEqualTo(run.runKey());
        assertThat(get(key).expectedModelEvents()).isEqualTo(1);
        assertThat(get(key).coverage()).isEqualTo("MISSING_MODEL");
        var next = executions.submit(run.workflowId(), new WorkflowDtos.RunRequest("public input", Map.of(), "new-submission"), "original-owner");
        assertThat(get(next.runKey() + ":1").expectedModelEvents()).isZero();
        assertThat(get(next.runKey() + ":2").expectedModelEvents()).isEqualTo(1);
        assertThat(sends).hasValue(1);
    }
    @Test void noModelDeclarationCannotTurnMismatchedReceiptIntoComplete() throws Exception {
        mode = "MISMATCH"; String key = submit("no-model-mismatch", NO_MODEL).runKey() + ":1";
        invoke(key); agentEvidence(key);
        assertThat(get(key).coverage()).isEqualTo("RECEIPT_TRACE_UNCONFIRMED");
    }
    @Test void invalidContractIsRejectedOnSaveAndAdmissionWithoutNewDenominators() {
        var run = submit("valid");
        String invalid = NO_MODEL.replace("NONE", "IGNORE_ALL");
        long operations = db.queryForObject("select count(*) from observation_operations", Long.class);
        assertThatThrownBy(() -> workflows.upsertNode(run.workflowId(), new WorkflowDtos.NodeRequest("one", "one", "AGENT", agent, null, 0, 0, invalid))).isInstanceOf(BusinessException.class);
        // A legacy/out-of-band saved draft must also be validated at submission.
        db.update("update workflow_nodes set config_json=? where workflow_id=? and node_key='one'", invalid, run.workflowId());
        assertThatThrownBy(() -> executions.submit(run.workflowId(), new WorkflowDtos.RunRequest("public input", Map.of(), "bad"), "original-owner")).isInstanceOf(BusinessException.class);
        assertThat(db.queryForObject("select count(*) from observation_operations", Long.class)).isEqualTo(operations);
        assertThat(sends).hasValue(0);
    }
    @Test void acceptedRouteIncludesUnreachedTasksAndReplayDoesNotEnrollAgain() {
        var run = submit("same"); assertThat(get(run.runKey() + ":1").coverage()).isEqualTo("PENDING");
        executions.submit(run.workflowId(), new WorkflowDtos.RunRequest("public input", Map.of(), "same"), "original-owner");
        assertThat(db.queryForObject("select count(*) from observation_operations where entry_kind='RUNTIME_TASK'", Long.class)).isEqualTo(2);
        db.update("update workflow_executions set status='FAILED' where run_key=?", run.runKey());
        assertThat(get(run.runKey() + ":2").coverage()).isEqualTo("NOT_EXECUTED"); assertThat(sends).hasValue(0);
    }
    @Test void actualTaskClientTransmitsOriginalTraceAndNeverReexecutesAnEnrolledDispatch() throws Exception {
        String key = submit("task").runKey() + ":1"; var receipt = invoke(key);
        String raw = db.queryForObject("select raw_trace_id from observation_operations where operation_key=?", String.class, key);
        assertThat(lastEnvelope.path("traceId").asText()).isEqualTo(raw); assertThat(receipt.runtimeTraceId()).isEqualTo(raw);
        assertThat(get(key).coverage()).isEqualTo("MISSING_BOTH"); evidence(key, "GATEWAY"); assertThat(get(key).coverage()).isEqualTo("COMPLETE");
        assertThatThrownBy(() -> invoke(key)).isInstanceOf(BusinessException.class); assertThat(sends).hasValue(1);
    }
    @Test void telemetryModelCannotReplaceGatewayEvidence() throws Exception {
        String key = submit("source").runKey() + ":1"; invoke(key); evidence(key, "TELEMETRY_API");
        assertThat(get(key).coverage()).isEqualTo("MISSING_MODEL");
    }
    @Test void legacyOrMismatchedReceiptIsNotSilentlyTreatedAsComplete() throws Exception {
        for (String variant : List.of("LEGACY", "MISMATCH")) {
            mode = variant; String key = submit(variant).runKey() + ":1"; invoke(key); evidence(key, "GATEWAY");
            assertThat(get(key).coverage()).isEqualTo("RECEIPT_TRACE_UNCONFIRMED"); assertThat(get(key).executionStatus()).isEqualTo("SUCCEEDED");
        }
    }
    @Test void lostTaskResponseRetainsUnknownAndDenominatorWithoutRetry() {
        mode = "BROKEN"; String key = submit("lost").runKey() + ":1"; assertThat(invoke(key).status()).isEqualTo("EXECUTION_UNKNOWN");
        assertThat(get(key).executionStatus()).isEqualTo("EXECUTION_UNKNOWN"); assertThat(sends).hasValue(1);
        assertThatThrownBy(() -> invoke(key)).isInstanceOf(BusinessException.class); assertThat(sends).hasValue(1);
    }
    @Test void actorReplacementCannotBorrowOriginalTaskEvidence() throws Exception {
        String key = submit("uid").runKey() + ":1"; invoke(key); evidence(key, "GATEWAY");
        db.update("update agent_runtime_instances set platform_actor_uid='replacement' where id=?", runtime.getId());
        assertThat(get(key).coverage()).isEqualTo("IDENTITY_UNCONFIRMED"); assertThat(get(key).modelEvents()).isNull();
    }
    @Test void chatUsesOriginalTraceAndFixedMetadataWithoutSavingMessage() throws Exception {
        String name = runtime.getInstanceKey().substring("eap-agents/".length());
        var request = new com.eap.platform.deployment.SubstrateDeploymentDtos.ChatRequest(UUID.randomUUID().toString(), "private-chat-input", List.of());
        var receipt = chats.submit(agent, name, request, "synthetic-owner");
        assertThat(receipt.status()).isEqualTo("SUCCEEDED");
        assertThat(receipt.traceId()).isEqualTo(db.queryForObject("select trace_id from observation_operations where entry_kind='CHAT'", String.class));
        assertThat(chats.submit(agent, name, request, "synthetic-owner").reply()).isNull();
        assertThat(sends).hasValue(1);
        String key = db.queryForObject("select operation_key from observation_operations where entry_kind='CHAT'", String.class);
        assertThat(lastEnvelope.path("traceId").asText()).isEqualTo(db.queryForObject("select raw_trace_id from observation_operations where operation_key=?", String.class, key));
        evidence(key, "GATEWAY"); assertThat(get(key).coverage()).isEqualTo("COMPLETE");
        assertThat(JSON.writeValueAsString(coverage.detail(operation(key)))).doesNotContain("private-chat-input", "fixture-dispatch", "public-reply");
    }
    @Test void failedChatIsAnUnknownOutcomeWithOneDispatchAndNoAutomaticRetry() {
        mode = "BROKEN"; String name = runtime.getInstanceKey().substring("eap-agents/".length());
        assertThatThrownBy(() -> deployments.chat(agent, name, "public-input")).isInstanceOf(BusinessException.class);
        String key = db.queryForObject("select operation_key from observation_operations where entry_kind='CHAT'", String.class);
        assertThat(get(key).executionStatus()).isEqualTo("EXECUTION_UNKNOWN"); assertThat(sends).hasValue(1);
    }
    @Test void disappearedDispatcherExpiresToUnknownOnReadWithoutRewritingEvidence() {
        String key = coverage.enrollChat(runtime); coverage.runtimeDispatch(key, runtime, Instant.now().plusSeconds(20));
        db.update("update observation_operations set dispatch_deadline=? where operation_key=?", java.sql.Timestamp.from(Instant.now().minusSeconds(1)), key);
        assertThat(get(key).executionStatus()).isEqualTo("EXECUTION_UNKNOWN");
        assertThat(db.queryForObject("select runtime_status from observation_operations where operation_key=?", String.class, key)).isEqualTo("RUNNING");
    }
    @Test void rollbackDoesNotCreateRouteDenominatorsAndHistoricalDispatchNeverRepairsThem() {
        assertThatThrownBy(() -> new TransactionTemplate(manager).executeWithoutResult(s -> { submit("rollback"); throw new IllegalStateException("fixture rollback"); })).isInstanceOf(IllegalStateException.class);
        assertThat(db.queryForObject("select count(*) from observation_operations", Long.class)).isZero();
        invoke("historical-task"); assertThat(lastEnvelope.has("traceId")).isFalse(); assertThat(db.queryForObject("select count(*) from observation_operations", Long.class)).isZero();
        assertThat(coverage.report(60).metrics().stream().filter(m -> m.entryKind().equals("LEGACY_EXTERNAL")).findFirst().orElseThrow().registered()).isNull();
    }
}
