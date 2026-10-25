package com.eap.platform.workflow;

import com.eap.platform.agent.*;
import com.eap.platform.common.BusinessException;
import com.eap.platform.registry.*;
import com.eap.platform.safety.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import java.net.InetSocketAddress;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest(properties = {"spring.flyway.enabled=true", "eap.workflow.execution.enabled=true",
        "eap.workflow.execution.worker-enabled=false", "eap.workflow.execution.allowed-hosts=127.0.0.1",
        "eap.workflow.execution.token=local-test-secret", "eap.workflow.execution.allow-insecure-http=true",
        "eap.workflow.execution.allow-private-networks=true", "eap.evaluation.async-worker-enabled=false"})
@ActiveProfiles("test")
class WorkflowExecutionIntegrationTests {
    static HttpServer server;
    static final AtomicReference<String> modelReason = new AtomicReference<>("");
    static final AtomicReference<String> reply = new AtomicReference<>("SUCCEEDED");
    static final AtomicReference<JsonNode> received = new AtomicReference<>();
    static final AtomicReference<JsonNode> output = new AtomicReference<>();
    static final AtomicReference<String> rawResponse = new AtomicReference<>();
    static final AtomicInteger delayMs = new AtomicInteger();
    static final AtomicInteger requests = new AtomicInteger();
    static final ObjectMapper json = new ObjectMapper();
    @Autowired ObjectMapper applicationJson;
    @Autowired RuntimeWorkService work;
    @Autowired WorkflowService workflows;
    @Autowired WorkflowExecutionService executions;
    @Autowired WorkflowExecutionRepository repository;
    @Autowired WorkflowSchedulingService scheduling;
    @Autowired WorkflowExecutionProperties properties;
    @Autowired org.springframework.jdbc.core.JdbcTemplate db;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactions;
    @Autowired AgentRegistryService registry;
    @Autowired AgentRepository agents;
    @MockitoBean GenseeSafetyService safety;
    @MockitoBean com.eap.platform.substrate.SubstrateClient substrateClient;
    @Autowired com.eap.platform.substrate.SubstrateProperties substrateProperties;
    @Autowired AgentRuntimeInstanceRepository runtimes;
    @MockitoBean RuntimeConnectionService connections;
    static final java.util.List<JsonNode> allReceived = new java.util.concurrent.CopyOnWriteArrayList<>();
    Long workflowId;
    Long agentId;

    @DynamicPropertySource
    static void startServer(DynamicPropertyRegistry ignored) throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/tasks", exchange -> {
            if (!"Bearer local-test-secret".equals(exchange.getRequestHeaders().getFirst("Authorization"))) {
                exchange.sendResponseHeaders(401, -1); exchange.close(); return;
            }
            JsonNode request = json.readTree(exchange.getRequestBody());
            received.set(request); allReceived.add(request); requests.incrementAndGet();
            String state = reply.get();
            try { Thread.sleep(delayMs.get()); } catch (InterruptedException exception) { Thread.currentThread().interrupt(); }
            Map<String, Object> response = new java.util.LinkedHashMap<>();
            response.put("taskKey", state.equals("WRONG_KEY") ? "wrong" : request.path("taskKey").asText());
            response.put("status", state);
            response.put("reasonCode", modelReason.get());
            if (request.path("context").has("eapTool")) response.put("tool", Map.of("state", "SUCCEEDED", "executed", true, "receiptConfirmed", true));
            if (output.get() != null) response.put("output", output.get());
            byte[] body = rawResponse.get() == null ? json.writeValueAsBytes(response)
                    : rawResponse.get().replace("TASK_KEY", request.path("taskKey").asText()).getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body); exchange.close();
        });
        server.createContext("/eap/state", exchange -> {
            byte[] body = "{\"tools\":{\"enabled\":true}}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length); exchange.getResponseBody().write(body); exchange.close();
        });
        server.start();
    }
    @AfterAll static void stop() { if (server != null) server.stop(0); }

    @BeforeEach
    void setup() {
        rawResponse.set(null); modelReason.set(""); allReceived.clear(); requests.set(0); delayMs.set(0); reply.set("SUCCEEDED"); output.set(null);
        allow(SafetyDecision.ALLOW);
        Agent agent = new Agent(); agent.setCode("WF-" + UUID.randomUUID()); agent.setName("Workflow test");
        agent.setOwnerDepartment("test"); agent.setOwnerUser("test"); agent.setCategory("test");
        agent.setLifecycleStatus(AgentLifecycleStatus.ACTIVE); agent.setRiskLevel(RiskLevel.LOW);
        agentId = agents.save(agent).getId();
        registry.registerRuntime(agentId, new AgentRegistryDtos.RuntimeInstanceRequest("wf-runtime-" + UUID.randomUUID(),
                "http://127.0.0.1:" + server.getAddress().getPort() + "/tasks", "EAP_HTTP_TASK_V1", false));
        workflowId = workflows.create(new WorkflowDtos.WorkflowRequest("WF-" + UUID.randomUUID(), "test workflow", "test")).id();
        node("start", "START", null); node("task", "AGENT", agentId); node("end", "END", null);
        workflows.addEdge(workflowId, new WorkflowDtos.EdgeRequest("start", "task", "always"));
        workflows.addEdge(workflowId, new WorkflowDtos.EdgeRequest("task", "end", "always"));
    }

    @Test
    void legacyProgressEntryCannotBypassNewOwnerFence() {
        var queued=executions.submit(workflowId,new WorkflowDtos.RunRequest("owner fence"),"operator");
        String owner=UUID.randomUUID().toString();
        db.update("update workflow_executions set status='RUNNING',owner_epoch=1,owner_id=?,started_at=CURRENT_TIMESTAMP where run_key=?",owner,queued.runKey());
        var run=repository.findByWorkflowIdAndRunKey(workflowId,queued.runKey()).orElseThrow();
        var tx=new org.springframework.transaction.support.TransactionTemplate(transactions);
        assertThat(tx.<Integer>execute(ignored->repository.progress(run.id,"[]","FAILED","legacy bypass",Instant.now()))).isEqualTo(0);
        assertThat(tx.<Integer>execute(ignored->repository.progressOwned(run.id,1,"wrong","[]","FAILED","stale",Instant.now()))).isEqualTo(0);
        assertThat(executions.get(workflowId,queued.runKey()).status()).isEqualTo("RUNNING");
        assertThat(tx.<Integer>execute(ignored->repository.progressOwned(run.id,1,owner,"[]","EXECUTION_UNKNOWN","fenced owner",Instant.now()))).isEqualTo(1);
    }
    @Test
    void expiredOwnerCannotWriteProgressOrDispatchNextNodeEvenWithSameRunningStatus() throws Exception {
        delayMs.set(600);
        var queued=executions.submit(workflowId,new WorkflowDtos.RunRequest("fenced original"),"operator");
        var worker=java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            var future=worker.submit(executions::processNext);
            long until=System.currentTimeMillis()+5000;
            while(requests.get()==0 && System.currentTimeMillis()<until)Thread.sleep(10);
            assertThat(requests.get()).isEqualTo(1);
            String frozen=db.queryForObject("select steps_json from workflow_executions where run_key=?",String.class,queued.runKey());
            db.update("update workflow_executions set owner_epoch=owner_epoch+1,owner_id=? where run_key=?",UUID.randomUUID().toString(),queued.runKey());
            future.get(5,java.util.concurrent.TimeUnit.SECONDS);
            assertThat(db.queryForObject("select steps_json from workflow_executions where run_key=?",String.class,queued.runKey())).isEqualTo(frozen);
            assertThat(executions.get(workflowId,queued.runKey()).status()).isEqualTo("RUNNING");
            assertThat(executions.get(workflowId,queued.runKey()).steps()).noneMatch(step->"end".equals(step.nodeKey()));
            assertThat(requests.get()).isEqualTo(1);
            db.update("update workflow_executions set status='EXECUTION_UNKNOWN' where run_key=?",queued.runKey());
        }finally{worker.shutdownNow();delayMs.set(0);}
    }

    @Test
    void executesSnapshotAndOnlyMatchingTerminalReceiptMeansSuccess() throws Exception {
        workflows.upsertNode(workflowId, new WorkflowDtos.NodeRequest("task", "task", "AGENT", agentId, null, 0, 0, "{\"mode\":\"audit\"}"));
        var queued = executions.submit(workflowId, new WorkflowDtos.RunRequest("actual task"), "operator");
        node("task", "END", null); // A later definition write does not replace the submitted path.
        executions.processNext();
        var result = executions.get(workflowId, queued.runKey());
        assertThat(result.status()).isEqualTo("SUCCEEDED");
        assertThat(result.executionMode()).isEqualTo("EAP_HTTP_TASK_V1");
        assertThat(result.steps()).extracting(WorkflowExecutionService.Step::status)
                .containsExactly("CHECK_PASSED", "SUCCEEDED", "CHECK_PASSED");
        assertThat(result.steps().get(1).runtimeBinding().instanceKey()).isEqualTo(result.steps().get(1).runtimeInstanceKey());
        assertThat(result.steps().get(1).runtimeBinding().deploymentKey()).isNull();
        assertThat(result.steps().get(1).runtimeBinding().capturedAt()).isBeforeOrEqualTo(result.completedAt());
        assertThat(requests.get()).isEqualTo(1);
        assertThat(received.get().path("taskKey").asText()).isEqualTo(result.steps().get(1).taskKey());
        assertThat(received.get().path("objective").asText()).isEqualTo("actual task");
        assertThat(json.readTree(received.get().path("configJson").asText()))
                .isEqualTo(json.readTree("{\"mode\":\"audit\"}"));
        verify(safety).evaluate(argThat(event -> event.target().equals("http://127.0.0.1:" + server.getAddress().getPort() + "/tasks")));
        assertThat(result.completedAt()).isNotNull();
        var profile=scheduling.report().runs().stream().filter(r->r.runKey().equals(queued.runKey())).findFirst().orElseThrow();
        assertThat(profile.phases()).extracting(WorkflowSchedulingService.Phase::kind).containsExactly("PREPARATION","TASK_WAIT");
        assertThat(profile.phases()).extracting(WorkflowSchedulingService.Phase::state).containsExactly("CONFIRMED","SUCCEEDED");
        assertThat(profile.phases()).allMatch(phase->phase.durationMs()!=null && phase.durationMs()>=0);
    }

    @Test
    void acknowledgmentOrWrongTaskKeyNeverMeansCompletion() {
        for (String response : new String[]{"RUNNING", "WRONG_KEY"}) {
            reply.set(response);
            var queued = executions.submit(workflowId, new WorkflowDtos.RunRequest("test"), "operator");
            executions.processNext();
            var result = executions.get(workflowId, queued.runKey());
            assertThat(result.status()).isEqualTo("EXECUTION_UNKNOWN");
            assertThat(result.steps()).hasSize(2); // END must not run after an unknown task.
            assertThat(result.steps().get(1).runtimeBinding()).isNotNull();
        }
        assertThat(requests.get()).isEqualTo(2); // No automatic retry.
    }

    @Test
    void runtimeFailureAndPolicyDenialStopExecution() {
        reply.set("FAILED");
        var failed = executions.submit(workflowId, new WorkflowDtos.RunRequest("test"), "operator");
        executions.processNext();
        assertThat(executions.get(workflowId, failed.runKey()).status()).isEqualTo("FAILED");
        allow(SafetyDecision.DENY);
        var denied = executions.submit(workflowId, new WorkflowDtos.RunRequest("test"), "operator");
        executions.processNext();
        assertThat(executions.get(workflowId, denied.runKey()).status()).isEqualTo("BLOCKED");
        assertThat(requests.get()).isEqualTo(1);
    }

    @Test
    void disabledExecutionAndUnknownHostCannotDispatch() {
        properties.setEnabled(false);
        try { assertThatThrownBy(() -> executions.submit(workflowId, new WorkflowDtos.RunRequest("test"), "operator"))
                .isInstanceOf(BusinessException.class); }
        finally { properties.setEnabled(true); }
        properties.setAllowedHosts("not-local.invalid");
        try {
            var queued = executions.submit(workflowId, new WorkflowDtos.RunRequest("test"), "operator");
            executions.processNext();
            assertThat(executions.get(workflowId, queued.runKey()).status()).isEqualTo("FAILED");
            assertThat(requests.get()).isZero();
        } finally { properties.setAllowedHosts("127.0.0.1"); }
    }

    @Test
    void abandonedExecutionBecomesUnknownAndIsNotRetried() {
        var queued = executions.submit(workflowId, new WorkflowDtos.RunRequest("test"), "operator");
        var row = repository.findByWorkflowIdAndRunKey(workflowId, queued.runKey()).orElseThrow();
        row.status = "RUNNING"; row.startedAt = Instant.now().minusSeconds(901); repository.save(row);
        executions.processNext();
        assertThat(executions.get(workflowId, queued.runKey()).status()).isEqualTo("EXECUTION_UNKNOWN");
        assertThat(requests.get()).isZero();
    }

    @Test
    void executionDeadlineStopsSlowRuntimeAndFollowingNodes() {
        properties.setTimeoutSeconds(1); delayMs.set(1500);
        try {
            var queued = executions.submit(workflowId, new WorkflowDtos.RunRequest("test deadline"), "operator");
            executions.processNext();
            var result = executions.get(workflowId, queued.runKey());
            assertThat(result.status()).isEqualTo("EXECUTION_UNKNOWN");
            assertThat(result.steps()).hasSize(2);
            assertThat(requests.get()).isEqualTo(1);
            executions.processNext();
            assertThat(requests.get()).isEqualTo(1);
        } finally { properties.setTimeoutSeconds(900); delayMs.set(0); }
    }

    @Test
    void concurrentRetriesReuseExecutionAndConflictingPayloadCannotReuseKey() throws Exception {
        String requestKey = "request-" + UUID.randomUUID();
        var request = new WorkflowDtos.RunRequest("once", Map.of("severity", "high", "approved", false, "tenant", "spoofed-priority", "priority", 99999), requestKey);
        java.util.List<String> keys = new java.util.ArrayList<>();
        try (var pool = java.util.concurrent.Executors.newFixedThreadPool(4)) {
            var start = new java.util.concurrent.CountDownLatch(1);
            java.util.List<java.util.concurrent.Future<String>> futures = new java.util.ArrayList<>();
            for (int i = 0; i < 4; i++) futures.add(pool.submit(() -> {
                start.await(); return executions.submit(workflowId, request, "operator").runKey();
            }));
            start.countDown();
            for (var future : futures) keys.add(future.get(10, java.util.concurrent.TimeUnit.SECONDS));
        }
        assertThat(keys.stream().distinct().count()).isEqualTo(1);
        assertThat(executions.list(workflowId)).hasSize(1);
        assertThat(db.queryForObject("select count(*) from workflow_schedule_entries s join workflow_executions e on e.id=s.execution_id where e.run_key=?",Long.class,keys.getFirst())).isEqualTo(1);
        assertThat(db.queryForObject("select s.group_key from workflow_schedule_entries s join workflow_executions e on e.id=s.execution_id where e.run_key=?",String.class,keys.getFirst())).isEqualTo(WorkflowSchedulingService.groupKey("operator"));
        assertThatThrownBy(() -> executions.submit(workflowId,
                new WorkflowDtos.RunRequest("changed", Map.of(), requestKey), "operator"))
                .isInstanceOf(com.eap.platform.common.ConflictException.class);
        executions.processNext();
        properties.setEnabled(false);
        try {
            node("task", "END", null);
            var replay = executions.submit(workflowId, request, "operator");
            assertThat(replay.runKey()).isEqualTo(keys.getFirst());
            assertThat(replay.status()).isEqualTo("SUCCEEDED");
        } finally { properties.setEnabled(true); }
        var other = executions.submit(workflowId, request, "another-operator");
        assertThat(other.runKey()).isNotEqualTo(keys.getFirst());
        executions.processNext();
        assertThat(requests.get()).isEqualTo(1);
    }

    @Test
    void handsOffOnlySelectedFrozenResultsAndKeepsRawContentOutOfMetadata() throws Exception {
        output.set(json.readTree("{\"finding\":\"handoff-proof\"}"));
        downstream("{\"inputFrom\":[\"task\"]}");
        var queued = executions.submit(workflowId, new WorkflowDtos.RunRequest("collaborate", Map.of("request", "original")), "operator");
        downstream("{}"); // Later edits must not remove a submitted handoff.
        executions.processNext();
        var result = executions.get(workflowId, queued.runKey());
        assertThat(result.status()).isEqualTo("SUCCEEDED");
        assertThat(requests.get()).isEqualTo(2);
        var previous = received.get().path("context").path("eapPreviousResults").path("task");
        assertThat(previous.path("output")).isEqualTo(output.get());
        assertThat(previous.path("taskKey").asText()).isEqualTo(queued.runKey() + ":1");
        assertThat(previous.path("agentId").asLong()).isEqualTo(agentId);
        assertThat(previous.path("outputSha256").asText()).matches("[0-9a-f]{64}");
        assertThat(received.get().path("context").path("request").asText()).isEqualTo("original");
        assertThat(result.steps()).allMatch(step -> step.output() == null);
        assertThat(executions.list(workflowId).getFirst().steps()).allMatch(step -> step.output() == null);
        assertThat(executions.outputs(workflowId, queued.runKey()).get("task")).isEqualTo(output.get());
        verify(safety).evaluate(argThat(event -> event.payloadJson().contains("handoff-proof")));
    }

    @Test
    void rejectsSpoofedContextAndInvalidOrForwardResultReferencesBeforeDispatch() {
        assertThatThrownBy(() -> executions.submit(workflowId,
                new WorkflowDtos.RunRequest("spoof", Map.of("eapPreviousResults", Map.of())), "operator"))
                .isInstanceOf(BusinessException.class);
        for (String config : new String[]{"{\"inputFrom\":[\"missing\"]}", "{\"inputFrom\":[\"end\"]}",
                "{\"inputFrom\":[\"start\"]}", "{\"inputFrom\":[\"task\",\"task\"]}", "{\"inputFrom\":\"task\"}"}) {
            downstream(config);
            assertThatThrownBy(() -> executions.submit(workflowId, new WorkflowDtos.RunRequest("invalid"), "operator"))
                    .isInstanceOf(BusinessException.class);
        }
        assertThat(requests.get()).isZero();
    }

    @Test
    void truncatedModelResultStopsDependentTaskAndPersistsReason() {
        reply.set("FAILED"); modelReason.set("MODEL_OUTPUT_TRUNCATED");
        output.set(json.getNodeFactory().textNode("PRIVATE_PARTIAL"));
        downstream("{\"inputFrom\":[\"task\"]}");
        var queued = executions.submit(workflowId, new WorkflowDtos.RunRequest("truncation regression"), "operator");
        executions.processNext();
        var result = executions.get(workflowId, queued.runKey());
        assertThat(result.status()).isEqualTo("FAILED");
        assertThat(result.steps()).anyMatch(step -> step.reason() != null && step.reason().contains("MODEL_OUTPUT_TRUNCATED"));
        assertThat(requests.get()).isEqualTo(1);
        assertThat(executions.outputs(workflowId, queued.runKey())).isEmpty();
        executions.processNext();
        assertThat(requests.get()).isEqualTo(1);
    }

    @Test
    void absentOutputStopsDependentTaskButUnboundTasksRemainCompatible() {
        downstream("{\"inputFrom\":[\"task\"]}");
        var queued = executions.submit(workflowId, new WorkflowDtos.RunRequest("missing output"), "operator");
        executions.processNext();
        assertThat(executions.get(workflowId, queued.runKey()).status()).isEqualTo("FAILED");
        assertThat(requests.get()).isEqualTo(1);
        assertThat(executions.outputs(workflowId, queued.runKey())).isEmpty();
    }

    @Test
    void oversizedOutputIsUnknownAndNeverTruncatedOrForwarded() {
        output.set(json.getNodeFactory().textNode("界".repeat(6000)));
        downstream("{\"inputFrom\":[\"task\"]}");
        var queued = executions.submit(workflowId, new WorkflowDtos.RunRequest("large output"), "operator");
        executions.processNext();
        assertThat(executions.get(workflowId, queued.runKey()).status()).isEqualTo("EXECUTION_UNKNOWN");
        assertThat(requests.get()).isEqualTo(1);
        assertThat(executions.outputs(workflowId, queued.runKey())).isEmpty();
    }

    @Test
    void mergedPayloadLimitStopsBeforeDispatch() {
        output.set(json.getNodeFactory().textNode("x".repeat(16000)));
        downstream("{\"inputFrom\":[\"task\"]}");
        var queued = executions.submit(workflowId, new WorkflowDtos.RunRequest("limit", Map.of("large", "x".repeat(45000))), "operator");
        executions.processNext();
        var result = executions.get(workflowId, queued.runKey());
        assertThat(result.status()).isEqualTo("FAILED");
        assertThat(result.reason()).contains("60KB");
        assertThat(requests.get()).isEqualTo(1);
    }

    @Test
    void downstreamGovernanceCanDenyActualMergedOutput() {
        output.set(json.getNodeFactory().textNode("restricted-handoff"));
        downstream("{\"inputFrom\":[\"task\"]}");
        when(safety.evaluate(any())).thenAnswer(invocation -> {
            SafetyDtos.SafetyEventRequest event = invocation.getArgument(0);
            SafetyDecision decision = event.payloadJson().contains("restricted-handoff") ? SafetyDecision.DENY : SafetyDecision.ALLOW;
            return new SafetyDtos.SafetyEvaluationResponse(null,
                    new SafetyDtos.DecisionResponse(1L, 1L, "test", decision, null, null, "test", null, null, null));
        });
        var queued = executions.submit(workflowId, new WorkflowDtos.RunRequest("governed handoff"), "operator");
        executions.processNext();
        assertThat(executions.get(workflowId, queued.runKey()).status()).isEqualTo("BLOCKED");
        assertThat(requests.get()).isEqualTo(1);
    }

    @Test
    void suspendsSuccessfulActorBeforeDispatchingNextNode() {
        configureSubstrateTask();
        downstream("{}");
        when(substrateClient.suspend(any())).thenAnswer(invocation -> {
            assertThat(requests.get()).isEqualTo(1); // Next task cannot have been dispatched yet.
            return new com.eap.platform.substrate.SubstrateClient.ActorState("SUSPENDED");
        });
        try {
            var queued = executions.submit(workflowId, new WorkflowDtos.RunRequest("release capacity"), "operator");
            executions.processNext();
            var result = executions.get(workflowId, queued.runKey());
            assertThat(result.status()).isEqualTo("SUCCEEDED");
            assertThat(requests.get()).isEqualTo(2);
            assertThat(result.steps().get(1).reclaimStatus()).isEqualTo("CONFIRMED");
            assertThat(result.steps().get(1).runtimeBinding()).isNotNull();
            var order = inOrder(substrateClient);
            order.verify(substrateClient).resume(any());
            order.verify(substrateClient).suspend(any());
            order.verify(substrateClient).resume(any());
        } finally { substrateProperties.setEnabled(false); }
    }

    @Test
    void unconfirmedSuspendStopsNextTaskWithoutLosingCompletedOutput() {
        configureSubstrateTask();
        downstream("{}");
        output.set(json.getNodeFactory().textNode("completed-result"));
        when(substrateClient.suspend(any())).thenReturn(new com.eap.platform.substrate.SubstrateClient.ActorState("RUNNING"));
        try {
            var queued = executions.submit(workflowId, new WorkflowDtos.RunRequest("release failed"), "operator");
            executions.processNext();
            var result = executions.get(workflowId, queued.runKey());
            assertThat(result.status()).isEqualTo("FAILED");
            assertThat(result.reason()).contains("休眠未确认");
            assertThat(result.steps().get(1).status()).isEqualTo("SUCCEEDED");
            assertThat(executions.outputs(workflowId, queued.runKey()).get("task")).isEqualTo(output.get());
            assertThat(requests.get()).isEqualTo(1);
        } finally { substrateProperties.setEnabled(false); }
    }

    @Test void reclaimsKnownFailedTaskWithoutReplacingBusinessReasonOrDispatchingNext() {
        configureSubstrateTask(); downstream("{}"); reply.set("FAILED"); modelReason.set("MODEL_OUTPUT_TRUNCATED");
        when(substrateClient.suspend(any())).thenReturn(new com.eap.platform.substrate.SubstrateClient.ActorState("SUSPENDED"));
        try {
            var queued = executions.submit(workflowId, new WorkflowDtos.RunRequest("failed reclaim"), "operator");
            executions.processNext(); var result = executions.get(workflowId, queued.runKey());
            assertThat(result.status()).isEqualTo("FAILED"); assertThat(result.reason()).contains("MODEL_OUTPUT_TRUNCATED");
            assertThat(result.steps().get(1).status()).isEqualTo("FAILED");
            assertThat(result.steps().get(1).reason()).isEqualTo(result.reason());
            assertThat(result.steps().get(1).reclaimStatus()).isEqualTo("CONFIRMED");
            assertThat(requests.get()).isEqualTo(1); verify(substrateClient).suspend(any());
            assertThat(db.queryForObject("select state from workflow_phase_observations where execution_id=(select id from workflow_executions where run_key=?) and phase_kind='RECLAIM'", String.class, queued.runKey())).isEqualTo("CONFIRMED");
        } finally { substrateProperties.setEnabled(false); }
    }

    @Test void failedReclaimKeepsOriginalFailureAndFencesNewDispatch() {
        configureSubstrateTask(); reply.set("FAILED"); modelReason.set("MODEL_OUTPUT_EMPTY");
        when(substrateClient.suspend(any())).thenReturn(new com.eap.platform.substrate.SubstrateClient.ActorState("RUNNING"));
        try {
            var queued = executions.submit(workflowId, new WorkflowDtos.RunRequest("failed reclaim unknown"), "operator");
            executions.processNext(); var result = executions.get(workflowId, queued.runKey());
            assertThat(result.status()).isEqualTo("FAILED"); assertThat(result.reason()).contains("MODEL_OUTPUT_EMPTY");
            assertThat(result.steps().get(1).reclaimStatus()).isEqualTo("UNCONFIRMED");
            assertThatThrownBy(() -> executions.submit(workflowId, new WorkflowDtos.RunRequest("do not race late pause"), "operator"))
                    .isInstanceOf(BusinessException.class).hasMessageContaining("回收状态未确认");
            assertThat(requests.get()).isEqualTo(1); verify(substrateClient, times(1)).resume(any());
        } finally { substrateProperties.setEnabled(false); }
    }

    @Test void sharedActorWithChatOrUnknownExperimentIsNotSuspended() {
        configureSubstrateTask(); reply.set("FAILED"); modelReason.set("MODEL_OUTPUT_EMPTY");
        String endpoint = runtimes.findByAgentId(agentId).getFirst().getEndpoint();
        try (var other = work.start(endpoint, "CHAT")) {
            assertThatThrownBy(() -> executions.submit(workflowId, new WorkflowDtos.RunRequest("busy reclaim"), "operator"))
                    .isInstanceOf(BusinessException.class).hasMessageContaining("占用");
            other.complete(false);
            assertThatThrownBy(() -> executions.submit(workflowId, new WorkflowDtos.RunRequest("unknown stays busy"), "operator"))
                    .isInstanceOf(BusinessException.class).hasMessageContaining("占用");
            assertThat(requests.get()).isZero();
            verify(substrateClient, never()).resume(any()); verify(substrateClient, never()).suspend(any());
        } finally { substrateProperties.setEnabled(false); }
    }

    @Test void occupancyAppearingAfterQueueRejectsDispatchWithoutChangingUnknownOrReplayingIdempotencyKey() {
        configureSubstrateTask();
        String endpoint = runtimes.findByAgentId(agentId).getFirst().getEndpoint();
        var request = new WorkflowDtos.RunRequest("late occupancy", Map.of(), "late-" + UUID.randomUUID());
        try {
            var queued = executions.submit(workflowId, request, "operator");
            try (var other = work.start(endpoint, "EXPERIMENT")) { other.complete(false); }
            assertThat(executions.submit(workflowId, request, "operator").runKey()).isEqualTo(queued.runKey());
            executions.processNext();
            var result = executions.get(workflowId, queued.runKey());
            assertThat(result.status()).isEqualTo("FAILED");
            assertThat(result.reason()).contains("未派发此任务");
            assertThat(requests.get()).isZero(); verify(substrateClient, never()).resume(any());
            assertThat(db.queryForObject("select count(*) from runtime_work_claims where actor_key=? and state='UNKNOWN'", Long.class, endpoint.substring(12))).isEqualTo(1);
            assertThat(db.queryForObject("select count(*) from runtime_work_claims where actor_key=? and work_kind='WORKFLOW'", Long.class, endpoint.substring(12))).isZero();
        } finally { substrateProperties.setEnabled(false); }
    }

    @Test void occupancyAppearingDuringTaskStillPreventsReclaimAndNextDispatch() {
        configureSubstrateTask(); downstream("{}");
        String endpoint = runtimes.findByAgentId(agentId).getFirst().getEndpoint();
        when(substrateClient.resume(any())).thenAnswer(invocation -> {
            try (var other = work.start(endpoint, "CHAT")) { other.complete(false); }
            return new com.eap.platform.substrate.SubstrateClient.ActorState("RUNNING");
        });
        try {
            var queued = executions.submit(workflowId, new WorkflowDtos.RunRequest("concurrent occupancy"), "operator");
            executions.processNext(); var result = executions.get(workflowId, queued.runKey());
            assertThat(result.status()).isEqualTo("FAILED");
            assertThat(result.steps().get(1).status()).isEqualTo("SUCCEEDED");
            assertThat(result.steps().get(1).reclaimStatus()).isEqualTo("SKIPPED_BUSY");
            assertThat(requests.get()).isEqualTo(1); verify(substrateClient, never()).suspend(any());
        } finally { substrateProperties.setEnabled(false); }
    }

    @Test void runtimeDriftRejectsQueuedReclaimWhenExistingBindingChangesToHttp() {
        configureSubstrateTask(); downstream("{}");
        var runtime = runtimes.findByAgentId(agentId).getFirst();
        String actor = runtime.getEndpoint().substring(12);
        var request = new WorkflowDtos.RunRequest("reclaim protocol drift", Map.of(), "drift-" + UUID.randomUUID());
        try {
            var queued = executions.submit(workflowId, request, "operator");
            registry.registerRuntime(agentId, new AgentRegistryDtos.RuntimeInstanceRequest(runtime.getInstanceKey(),
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/tasks", "EAP_HTTP_TASK_V1", false));
            executions.processNext();
            var result = executions.get(workflowId, queued.runKey());
            assertThat(result.status()).isEqualTo("FAILED");
            assertThat(result.reason()).contains("不支持", "未派发");
            assertThat(result.steps()).extracting(step -> step.nodeKey()).containsExactly("start", "task");
            assertThat(result.steps().get(1).status()).isEqualTo("FAILED");
            assertThat(result.steps().get(1).runtimeInstanceKey()).isEqualTo(runtime.getInstanceKey());
            assertThat(result.steps().get(1).runtimeBinding()).isNotNull();
            assertThat(result.steps().get(1).httpStatus()).isNull();
            assertThat(requests.get()).isZero();
            assertThat(db.queryForObject("select count(*) from runtime_work_claims where actor_key=?", Long.class, actor)).isZero();
            verify(substrateClient, never()).resume(any()); verify(substrateClient, never()).suspend(any());
            assertThat(executions.submit(workflowId, request, "operator").runKey()).isEqualTo(queued.runKey());
            assertThat(requests.get()).isZero();
        } finally { substrateProperties.setEnabled(false); }
    }

    @Test void runtimeDriftRejectsQueuedReclaimWhenNewHttpInstanceReplacesUnavailableActor() {
        configureSubstrateTask();
        var runtime = runtimes.findByAgentId(agentId).getFirst();
        try {
            var queued = executions.submit(workflowId, new WorkflowDtos.RunRequest("reclaim fallback drift"), "operator");
            db.update("update agent_runtime_instances set status='OFFLINE' where instance_key=?", runtime.getInstanceKey());
            String replacement = "http-replacement-" + UUID.randomUUID();
            registry.registerRuntime(agentId, new AgentRegistryDtos.RuntimeInstanceRequest(replacement,
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/tasks", "EAP_HTTP_TASK_V1", false));
            executions.processNext(); var result = executions.get(workflowId, queued.runKey());
            assertThat(result.status()).isEqualTo("FAILED");
            assertThat(result.reason()).contains("不支持", "未派发");
            assertThat(result.steps().get(1).runtimeInstanceKey()).isEqualTo(replacement);
            assertThat(requests.get()).isZero();
            verify(substrateClient, never()).resume(any()); verify(substrateClient, never()).suspend(any());
        } finally { substrateProperties.setEnabled(false); }
    }

    @Test void runtimeDriftAllowsOrdinaryTaskWithoutAutomaticReclaim() {
        configureSubstrateTask(); node("task", "AGENT", agentId);
        var runtime = runtimes.findByAgentId(agentId).getFirst();
        try {
            var queued = executions.submit(workflowId, new WorkflowDtos.RunRequest("ordinary protocol drift"), "operator");
            registry.registerRuntime(agentId, new AgentRegistryDtos.RuntimeInstanceRequest(runtime.getInstanceKey(),
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/tasks", "EAP_HTTP_TASK_V1", false));
            executions.processNext(); var result = executions.get(workflowId, queued.runKey());
            assertThat(result.status()).isEqualTo("SUCCEEDED");
            assertThat(requests.get()).isEqualTo(1);
            assertThat(result.steps().get(1).reclaimStatus()).isNull();
            verify(substrateClient, never()).resume(any()); verify(substrateClient, never()).suspend(any());
        } finally { substrateProperties.setEnabled(false); }
    }

    @Test void negativeKnownFailureMustReclaim() {
        configureSubstrateTask(); reply.set("FAILED"); modelReason.set("MODEL_OUTPUT_EMPTY");
        when(substrateClient.suspend(any())).thenReturn(new com.eap.platform.substrate.SubstrateClient.ActorState("SUSPENDED"));
        try {
            var queued = executions.submit(workflowId, new WorkflowDtos.RunRequest("negative known failure"), "operator");
            executions.processNext();
            assertThat(executions.get(workflowId, queued.runKey()).status()).isEqualTo("FAILED");
            assertThat(requests.get()).isEqualTo(1);
            verify(substrateClient).suspend(any());
        } finally { substrateProperties.setEnabled(false); }
    }

    @Test
    void unknownTaskResultMustNeverSuspendActor() {
        configureSubstrateTask();
        reply.set("RUNNING");
        try {
            var queued = executions.submit(workflowId, new WorkflowDtos.RunRequest("unknown"), "operator");
            executions.processNext();
            assertThat(executions.get(workflowId, queued.runKey()).status()).isEqualTo("EXECUTION_UNKNOWN");
            verify(substrateClient, never()).suspend(any());
        } finally { substrateProperties.setEnabled(false); }
    }

    @Test
    void rejectsSuspendForHttpRuntimeAndNonBooleanConfiguration() {
        for (String config : new String[]{"{\"suspendAfterTask\":true}", "{\"suspendAfterTask\":\"true\"}"}) {
            downstream(config);
            assertThatThrownBy(() -> executions.submit(workflowId, new WorkflowDtos.RunRequest("invalid release"), "operator"))
                    .isInstanceOf(BusinessException.class);
        }
        assertThat(requests.get()).isZero();
    }

    @Test
    void savedToolsAreFrozenAndNeverLeakIntoOtherNodes() {
        configureSubstrateTask();
        var runtime = runtimes.findByAgentId(agentId).getFirst();
        org.springframework.test.util.ReflectionTestUtils.setField(runtime, "importedActorUid", "tool-fixture");
        runtimes.save(runtime);
        when(connections.executable(any())).thenReturn(true);
        when(connections.dispatchToken(any())).thenReturn("local-test-secret");
        when(substrateClient.inspect(any())).thenReturn(new com.eap.platform.substrate.SubstrateClient.ActorInventory("test", "actor", "tool-fixture", "template", "RUNNING"));
        String config = "{\"eapTool\":{\"name\":\"calculate_sum\",\"arguments\":{\"numbers\":[10,20,12]}}}";
        workflows.upsertNode(workflowId, new WorkflowDtos.NodeRequest("task", "calculate", "AGENT", agentId, null, 0, 0, config));
        downstream("{}");
        try {
            var queued = executions.submit(workflowId, new WorkflowDtos.RunRequest("tool and model"), "operator");
            node("task", "AGENT", agentId);
            executions.processNext();
            assertThat(executions.get(workflowId, queued.runKey()).status()).isEqualTo("SUCCEEDED");
            assertThat(allReceived).hasSize(2);
            assertThat(allReceived.get(0).path("context").path("eapTool").path("arguments").path("numbers").toString()).isEqualTo("[10,20,12]");
            assertThat(allReceived.get(1).path("context").has("eapTool")).isFalse();
        } finally { substrateProperties.setEnabled(false); }
    }

    @Test
    void globalToolsAndSharedCredentialRuntimesCannotQueueNewToolTasks() {
        assertThatThrownBy(() -> executions.submit(workflowId,
                new WorkflowDtos.RunRequest("invalid", Map.of("eapTool", Map.of("name", "calculate_sum"))), "operator"))
                .isInstanceOf(BusinessException.class).hasMessageContaining("节点配置");
        workflows.upsertNode(workflowId, new WorkflowDtos.NodeRequest("task", "tool", "AGENT", agentId, null, 0, 0,
                "{\"eapTool\":{\"name\":\"calculate_sum\",\"arguments\":{\"numbers\":[1]}}}"));
        assertThatThrownBy(() -> executions.submit(workflowId, new WorkflowDtos.RunRequest("invalid"), "operator"))
                .isInstanceOf(BusinessException.class).hasMessageContaining("实例专属接入");
        assertThat(executions.list(workflowId)).isEmpty();
        assertThat(requests.get()).isZero();
    }

    @Test
    void structuredTextUsesFrozenContractAndHandsOffParsedJsonWithMatchingDigest() throws Exception {
        String config = WorkflowOutputContractTests.config("JSON_TEXT", WorkflowOutputContractTests.SCHEMA);
        workflows.upsertNode(workflowId, new WorkflowDtos.NodeRequest("task", "task", "AGENT", agentId, null, 0, 0, config));
        output.set(json.getNodeFactory().textNode("{\"summary\":\"private-handoff\"}"));
        downstream("{\"inputFrom\":[\"task\"]}");
        String frozenConfig = workflows.detail(workflowId).nodes().stream().filter(n -> n.nodeKey().equals("task")).findFirst().orElseThrow().configJson();
        var queued = executions.submit(workflowId, new WorkflowDtos.RunRequest("validated handoff"), "operator");
        // Changing the current node cannot change the already submitted contract.
        workflows.upsertNode(workflowId, new WorkflowDtos.NodeRequest("task", "task", "AGENT", agentId, null, 0, 0,
                config.replace("summary", "changed")));
        executions.processNext();
        var result = executions.get(workflowId, queued.runKey());
        assertThat(result.status()).isEqualTo("SUCCEEDED"); assertThat(requests.get()).isEqualTo(2);
        var validation = result.steps().get(1).resultValidation();
        assertThat(validation.status()).isEqualTo("PASSED");
        assertThat(validation.contractSha256()).isEqualTo(WorkflowOutputContract.read(frozenConfig,"AGENT",agentId).sha256());
        var handed = allReceived.get(1).path("context").path("eapPreviousResults").path("task");
        assertThat(handed.path("output").isObject()).isTrue();
        assertThat(handed.path("output").path("summary").asText()).isEqualTo("private-handoff");
        assertThat(handed.path("outputSha256").asText()).isEqualTo(java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(json.writeValueAsBytes(handed.path("output")))));
        assertThat(handed.path("originalOutputSha256").asText()).isEqualTo(result.steps().get(1).outputSha256());
        assertThat(executions.outputs(workflowId, queued.runKey()).get("task")).isEqualTo(output.get());
        assertThat(applicationJson.writeValueAsString(result)).doesNotContain("private-handoff", "summary");
        assertThat(executions.get(workflowId,queued.runKey()).steps().get(1).resultValidation()).isEqualTo(validation);
    }

    @Test
    void rejectedOutputStopsDownstreamWithoutRewritingSuccessfulReceiptOrRetrying() throws Exception {
        workflows.upsertNode(workflowId,new WorkflowDtos.NodeRequest("task","task","AGENT",agentId,null,0,0,
                WorkflowOutputContractTests.config("OUTPUT",WorkflowOutputContractTests.SCHEMA)));
        output.set(json.readTree("{\"summary\":42}")); downstream("{}");
        var queued=executions.submit(workflowId,new WorkflowDtos.RunRequest("reject result",Map.of(),"contract-once"),"operator");
        executions.processNext(); executions.processNext();
        var result=executions.get(workflowId,queued.runKey());
        assertThat(result.status()).isEqualTo("FAILED"); assertThat(requests.get()).isEqualTo(1);
        assertThat(result.steps()).hasSize(2);
        assertThat(result.steps().get(1).status()).isEqualTo("SUCCEEDED");
        assertThat(result.steps().get(1).resultValidation().code()).isEqualTo("TYPE_MISMATCH");
        assertThat(executions.outputs(workflowId,queued.runKey()).get("task")).isEqualTo(output.get());
        assertThat(executions.submit(workflowId,new WorkflowDtos.RunRequest("reject result",Map.of(),"contract-once"),"operator").runKey()).isEqualTo(queued.runKey());
        assertThat(requests.get()).isEqualTo(1);
    }

    @Test
    void invalidStructuredResultStillConfirmsResourceReleaseBeforeFinishing() throws Exception {
        configureSubstrateTask(); downstream("{}"); output.set(json.readTree("{\"summary\":42}"));
        String config=WorkflowOutputContractTests.config("OUTPUT",WorkflowOutputContractTests.SCHEMA);
        workflows.upsertNode(workflowId,new WorkflowDtos.NodeRequest("task","task","AGENT",agentId,null,0,0,
                config.replaceFirst("\\{","{\"suspendAfterTask\":true,")));
        when(substrateClient.suspend(any())).thenReturn(new com.eap.platform.substrate.SubstrateClient.ActorState("SUSPENDED"));
        try {
            var queued=executions.submit(workflowId,new WorkflowDtos.RunRequest("reject and reclaim"),"operator");
            executions.processNext(); var result=executions.get(workflowId,queued.runKey());
            assertThat(result.status()).isEqualTo("FAILED"); assertThat(requests.get()).isEqualTo(1);
            var step=result.steps().get(1);
            assertThat(step.status()).isEqualTo("SUCCEEDED"); assertThat(step.resultValidation().status()).isEqualTo("REJECTED");
            assertThat(step.reclaimStatus()).isEqualTo("CONFIRMED"); assertThat(step.runtimeBinding()).isNotNull();
            verify(substrateClient).suspend(any());
        } finally { substrateProperties.setEnabled(false); }
    }

    @Test
    void contractsDoNotTurnUnknownOrFailedTasksIntoValidationFailures() throws Exception {
        workflows.upsertNode(workflowId,new WorkflowDtos.NodeRequest("task","task","AGENT",agentId,null,0,0,
                WorkflowOutputContractTests.config("OUTPUT",WorkflowOutputContractTests.SCHEMA)));
        downstream("{}"); output.set(json.readTree("{\"summary\":\"ok\"}"));
        for(String state:new String[]{"WRONG_KEY","FAILED"}) {
            reply.set(state); var queued=executions.submit(workflowId,new WorkflowDtos.RunRequest("receipt first"),"operator");
            executions.processNext(); var result=executions.get(workflowId,queued.runKey());
            assertThat(result.status()).isEqualTo(state.equals("WRONG_KEY")?"EXECUTION_UNKNOWN":"FAILED");
            assertThat(result.steps().get(1).resultValidation().status()).isEqualTo("NOT_EVALUATED");
            assertThat(executions.outputs(workflowId,queued.runKey())).isEmpty();
        }
        assertThat(requests.get()).isEqualTo(2);
    }

    @Test
    void invalidSavedContractIsRejectedBeforeEnrollmentOrDispatch() {
        db.update("update workflow_nodes set config_json=? where workflow_id=? and node_key='task'",
                WorkflowOutputContractTests.config("OUTPUT",WorkflowOutputContractTests.SCHEMA).replace("EAP_JSON_V1","UNSUPPORTED"),workflowId);
        assertThatThrownBy(()->executions.submit(workflowId,new WorkflowDtos.RunRequest("invalid contract"),"operator")).isInstanceOf(BusinessException.class);
        assertThat(executions.list(workflowId)).isEmpty(); assertThat(requests.get()).isZero();
    }

    @Test
    void ambiguousRuntimeEnvelopesRemainUnknownAndNeverFeedDownstream() {
        downstream("{}");
        for(String raw:new String[]{"{\"taskKey\":\"TASK_KEY\",\"status\":\"SUCCEEDED\",\"output\":{},\"output\":{}}",
                "{\"taskKey\":\"TASK_KEY\",\"status\":\"SUCCEEDED\"} {}"}) {
            rawResponse.set(raw); var queued=executions.submit(workflowId,new WorkflowDtos.RunRequest("ambiguous receipt"),"operator");
            executions.processNext(); assertThat(executions.get(workflowId,queued.runKey()).status()).isEqualTo("EXECUTION_UNKNOWN");
        }
        assertThat(requests.get()).isEqualTo(2);
    }

    private void configureSubstrateTask() {
        var runtime = runtimes.findByAgentId(agentId).getFirst();
        registry.registerRuntime(agentId, new AgentRegistryDtos.RuntimeInstanceRequest(runtime.getInstanceKey(),
                "substrate://test/actor-" + agentId, "SUBSTRATE", false));
        substrateProperties.setEnabled(true);
        substrateProperties.setRouterUrl("http://127.0.0.1:" + server.getAddress().getPort());
        substrateProperties.setTaskPath("/tasks");
        when(substrateClient.resume(any())).thenReturn(new com.eap.platform.substrate.SubstrateClient.ActorState("RUNNING"));
        workflows.upsertNode(workflowId, new WorkflowDtos.NodeRequest("task", "task", "AGENT", agentId, null, 0, 0,
                "{\"suspendAfterTask\":true}"));
    }

    private void downstream(String config) {
        workflows.upsertNode(workflowId, new WorkflowDtos.NodeRequest("end", "consumer", "AGENT", agentId, null, 0, 0, config));
    }

    private void allow(SafetyDecision decision) {
        when(safety.evaluate(any())).thenReturn(new SafetyDtos.SafetyEvaluationResponse(null,
                new SafetyDtos.DecisionResponse(1L, 1L, "test", decision, null, null, "test", null, null, null)));
    }
    private void node(String key, String type, Long id) {
        workflows.upsertNode(workflowId, new WorkflowDtos.NodeRequest(key, key, type, id, null, 0, 0, "{}"));
    }
}
