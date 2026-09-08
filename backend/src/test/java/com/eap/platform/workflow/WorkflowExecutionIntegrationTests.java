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
    static final AtomicReference<String> reply = new AtomicReference<>("SUCCEEDED");
    static final AtomicReference<JsonNode> received = new AtomicReference<>();
    static final AtomicInteger delayMs = new AtomicInteger();
    static final AtomicInteger requests = new AtomicInteger();
    static final ObjectMapper json = new ObjectMapper();
    @Autowired WorkflowService workflows;
    @Autowired WorkflowExecutionService executions;
    @Autowired WorkflowExecutionRepository repository;
    @Autowired WorkflowExecutionProperties properties;
    @Autowired AgentRegistryService registry;
    @Autowired AgentRepository agents;
    @MockitoBean GenseeSafetyService safety;
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
            received.set(request); requests.incrementAndGet();
            String state = reply.get();
            try { Thread.sleep(delayMs.get()); } catch (InterruptedException exception) { Thread.currentThread().interrupt(); }
            byte[] body = json.writeValueAsBytes(Map.of("taskKey", state.equals("WRONG_KEY") ? "wrong" : request.path("taskKey").asText(),
                    "status", state));
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body); exchange.close();
        });
        server.start();
    }
    @AfterAll static void stop() { if (server != null) server.stop(0); }

    @BeforeEach
    void setup() {
        requests.set(0); delayMs.set(0); reply.set("SUCCEEDED");
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
    void executesSnapshotAndOnlyMatchingTerminalReceiptMeansSuccess() {
        workflows.upsertNode(workflowId, new WorkflowDtos.NodeRequest("task", "task", "AGENT", agentId, null, 0, 0, "{\"mode\":\"audit\"}"));
        var queued = executions.submit(workflowId, new WorkflowDtos.RunRequest("actual task"), "operator");
        node("task", "END", null); // A later definition write does not replace the submitted path.
        executions.processNext();
        var result = executions.get(workflowId, queued.runKey());
        assertThat(result.status()).isEqualTo("SUCCEEDED");
        assertThat(result.executionMode()).isEqualTo("EAP_HTTP_TASK_V1");
        assertThat(result.steps()).extracting(WorkflowExecutionService.Step::status)
                .containsExactly("CHECK_PASSED", "SUCCEEDED", "CHECK_PASSED");
        assertThat(requests.get()).isEqualTo(1);
        assertThat(received.get().path("taskKey").asText()).isEqualTo(result.steps().get(1).taskKey());
        assertThat(received.get().path("objective").asText()).isEqualTo("actual task");
        assertThat(received.get().path("configJson").asText()).isEqualTo("{\"mode\":\"audit\"}");
        verify(safety).evaluate(argThat(event -> event.target().equals("http://127.0.0.1:" + server.getAddress().getPort() + "/tasks")));
        assertThat(result.completedAt()).isNotNull();
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
        var request = new WorkflowDtos.RunRequest("once", Map.of("severity", "high", "approved", false), requestKey);
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

    private void allow(SafetyDecision decision) {
        when(safety.evaluate(any())).thenReturn(new SafetyDtos.SafetyEvaluationResponse(null,
                new SafetyDtos.DecisionResponse(1L, 1L, "test", decision, null, null, "test", null, null, null)));
    }
    private void node(String key, String type, Long id) {
        workflows.upsertNode(workflowId, new WorkflowDtos.NodeRequest(key, key, type, id, null, 0, 0, "{}"));
    }
}
