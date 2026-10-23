package com.eap.platform.workflow;

import com.eap.platform.registry.AgentRuntimeInstance;
import com.eap.platform.substrate.SubstrateActorRef;
import com.eap.platform.substrate.SubstrateClient;
import com.eap.platform.substrate.SubstrateProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class WorkflowTaskClientSubstrateTests {
    private final ObjectMapper json = new ObjectMapper();
    private final AtomicReference<String> routedActor = new AtomicReference<>();
    private final AtomicReference<String> path = new AtomicReference<>();
    private final SubstrateClient substrateClient = mock(SubstrateClient.class);
    private final com.eap.platform.registry.RuntimeConnectionService connections = mock(com.eap.platform.registry.RuntimeConnectionService.class);
    private final java.util.concurrent.atomic.AtomicInteger taskCalls = new java.util.concurrent.atomic.AtomicInteger();
    private final AtomicReference<String> toolState = new AtomicReference<>("{}");
    private final AtomicReference<Map<String, Object>> toolReceipt = new AtomicReference<>(Map.of());
    private final com.eap.platform.registry.ScopedToolService scopedTools=mock(com.eap.platform.registry.ScopedToolService.class);
    private final AtomicReference<Map<String,Object>> output=new AtomicReference<>(Map.of());
    private final AtomicReference<String> responseStatus = new AtomicReference<>("SUCCEEDED");
    private final AtomicReference<String> reasonCode = new AtomicReference<>("");
    private HttpServer router;
    private WorkflowTaskClient client;

    @BeforeEach
    void setUp() throws Exception {
        router = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        router.createContext("/", exchange -> {
            routedActor.set(exchange.getRequestHeaders().getFirst("ate-target-actor"));
            path.set(exchange.getRequestURI().getPath());
            if (exchange.getRequestURI().getPath().equals("/eap/state")) {
                assertThat(exchange.getRequestHeaders().getFirst("Authorization")).isEqualTo("Bearer scoped-test");
                byte[] body = toolState.get().getBytes(java.nio.charset.StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, body.length); exchange.getResponseBody().write(body); exchange.close(); return;
            }
            taskCalls.incrementAndGet();
            JsonNode request = json.readTree(exchange.getRequestBody());
            byte[] body = json.writeValueAsBytes(Map.of("taskKey", request.path("taskKey").asText(), "status", responseStatus.get(), "reasonCode", reasonCode.get(), "tool", toolReceipt.get(), "output", output.get()));
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        router.start();
        WorkflowExecutionProperties properties = new WorkflowExecutionProperties();
        properties.setEnabled(true);
        properties.setToken("secret");
        // The allowlist deliberately excludes the router: Substrate targets come from server config, not registration.
        properties.setAllowedHosts("tasks.example.com");
        SubstrateProperties substrate = new SubstrateProperties();
        substrate.setEnabled(true);
        substrate.setRouterUrl("http://127.0.0.1:" + router.getAddress().getPort() + "/");
        client = new WorkflowTaskClient(properties, json, substrate, substrateClient, connections, scopedTools);
    }

    @AfterEach
    void tearDown() {
        router.stop(0);
        client.close();
    }

    @Test
    void wakesActorThenRoutesTaskThroughRouterWithTargetHeader() {
        when(substrateClient.resume(new SubstrateActorRef("eap-demo", "session-1"))).thenReturn(new SubstrateClient.ActorState("RUNNING"));

        var receipt = client.invoke(runtime("substrate://eap-demo/session-1"), Map.of("taskKey", "wf-1:1"), Instant.now().plusSeconds(30));

        assertThat(receipt.status()).isEqualTo("SUCCEEDED");
        assertThat(routedActor.get()).isEqualTo("eap-demo/session-1");
        assertThat(path.get()).isEqualTo("/eap/task");
        verify(substrateClient).resume(new SubstrateActorRef("eap-demo", "session-1"));
    }

    @Test void incompleteModelOutputCannotBeHandedOffAndReasonIsSafe() {
        responseStatus.set("FAILED");
        output.set(Map.of("partial", "PRIVATE_CONTENT"));
        for (String code : new String[]{"MODEL_OUTPUT_TRUNCATED", "MODEL_OUTPUT_EMPTY", "MODEL_OUTPUT_FILTERED", "MODEL_TOOL_CALL_UNSUPPORTED", "MODEL_FINISH_UNCONFIRMED"}) {
            reasonCode.set(code);
            var receipt = client.invoke(runtime("substrate://eap-demo/session-1"), Map.of("taskKey", "sample:1"), Instant.now().plusSeconds(30));
            assertThat(receipt.status()).isEqualTo("FAILED");
            assertThat(receipt.output()).isNull();
            assertThat(receipt.reason()).contains(code).doesNotContain("PRIVATE_CONTENT");
        }
        reasonCode.set("PRIVATE_PROVIDER_ERROR");
        var receipt = client.invoke(runtime("substrate://eap-demo/session-1"), Map.of("taskKey", "sample:1"), Instant.now().plusSeconds(30));
        assertThat(receipt.reason()).isEqualTo("Runtime 已确认任务失败");
        assertThat(taskCalls.get()).isEqualTo(6);
    }

    @Test
    void dispatchBarrierRunsAfterWakeupAndCanBlockTaskAfterOwnershipLoss() {
        assertThatThrownBy(()->client.invoke(runtime("substrate://eap-demo/session-1"), Map.of("taskKey","wf-1:1"), Instant.now().plusSeconds(30), ()->{
            verify(substrateClient).resume(new SubstrateActorRef("eap-demo","session-1"));
            assertThat(taskCalls.get()).isZero();
            throw new IllegalStateException("responsibility lost");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(taskCalls.get()).isZero();
    }
    @Test
    void failedWakeupDoesNotReportPreparationComplete() {
        when(substrateClient.resume(any())).thenThrow(new SubstrateClient.SubstrateException("RESOURCE_EXHAUSTED", "no free workers"));
        var prepared=new java.util.concurrent.atomic.AtomicInteger();
        assertThat(client.invoke(runtime("substrate://eap-demo/session-1"),Map.of("taskKey","wf-1:1"),Instant.now().plusSeconds(30),prepared::incrementAndGet).status()).isEqualTo("FAILED");
        assertThat(prepared.get()).isZero();assertThat(taskCalls.get()).isZero();
    }
    @Test
    void failedWakeupIsFailedAndNothingIsSent() {
        when(substrateClient.resume(any())).thenThrow(new SubstrateClient.SubstrateException("RESOURCE_EXHAUSTED", "no free workers"));

        var receipt = client.invoke(runtime("substrate://eap-demo/session-1"), Map.of("taskKey", "wf-1:1"), Instant.now().plusSeconds(30));

        assertThat(receipt.status()).isEqualTo("FAILED");
        assertThat(receipt.reason()).contains("RESOURCE_EXHAUSTED");
        assertThat(routedActor.get()).isNull();
    }

    @Test
    void registeredEndpointNeverBecomesTheRequestUrl() {
        var receipt = client.invoke(runtime("substrate://evil.example.com/x?y"), Map.of("taskKey", "wf-1:1"), Instant.now().plusSeconds(30));

        assertThat(receipt.status()).isEqualTo("FAILED");
        verifyNoInteractions(substrateClient);
        assertThat(routedActor.get()).isNull();
    }

    @Test
    void oldOrDisabledRuntimeNeverReceivesToolTaskOrUnsafelySuspendsSharedActor() {
        var runtime = scopedRuntime();
        for (String state : new String[]{"{}", "{\"tools\":{\"enabled\":false}}", "{\"tools\":{\"enabled\":\"true\"}}", "broken"}) {
            toolState.set(state);
            var receipt = client.invoke(runtime, toolPayload(), Instant.now().plusSeconds(30));
            assertThat(receipt.status()).isEqualTo("FAILED");
            assertThat(receipt.reason()).contains("未派发任务");
        }
        assertThat(taskCalls.get()).isZero();
        verify(substrateClient, never()).suspend(any());
    }

    @Test
    void toolNeedsConfirmedReceiptAndDoesNotRetryAnUncertainResult() {
        var runtime = scopedRuntime();
        toolState.set("{\"tools\":{\"enabled\":true}}");
        assertThat(client.invoke(runtime, toolPayload(), Instant.now().plusSeconds(30)).status()).isEqualTo("EXECUTION_UNKNOWN");
        assertThat(taskCalls.get()).isEqualTo(1);
        verify(substrateClient, never()).suspend(any());
        toolReceipt.set(Map.of("executed", true, "state", "SUCCEEDED", "receiptConfirmed", true));
        assertThat(client.invoke(runtime, toolPayload(), Instant.now().plusSeconds(30)).status()).isEqualTo("SUCCEEDED");
        assertThat(taskCalls.get()).isEqualTo(2);
    }

    @Test
    void scopedToolNeedsIndependentStoreProofAndOldReceiptCannotSubstitute() {
        var runtime=scopedRuntime();when(runtime.getInstanceKey()).thenReturn("eap-demo/session-1");
        toolState.set("{\"tools\":{\"enabled\":true}}");
        var args=Map.of("grantId","00000000-0000-4000-8000-000000000002","operationId","00000000-0000-4000-8000-000000000003","resourceId","poc_test","delta",3);
        var payload=Map.<String,Object>of("taskKey","counter-test","context",Map.of("eapTool",Map.of("name","controlled_counter_increment","arguments",args)));
        toolReceipt.set(Map.of("executed",true,"state","TOOL_STORE_VERIFIED","receiptConfirmed",true));
        output.set(Map.of("counterValue",3,"evidenceSource","TOOL_STORE_VERIFIED"));
        when(scopedTools.verifyEffect(eq("eap-demo/session-1"),any(),any())).thenReturn(false);
        assertThat(client.invoke(runtime,payload,Instant.now().plusSeconds(30)).status()).isEqualTo("EXECUTION_UNKNOWN");
        when(scopedTools.verifyEffect(eq("eap-demo/session-1"),any(),any())).thenReturn(true);
        assertThat(client.invoke(runtime,payload,Instant.now().plusSeconds(30)).status()).isEqualTo("SUCCEEDED");
        toolReceipt.set(Map.of("executed",true,"state","SUCCEEDED","receiptConfirmed",true));
        assertThat(client.invoke(runtime,payload,Instant.now().plusSeconds(30)).status()).isEqualTo("EXECUTION_UNKNOWN");
        assertThat(taskCalls.get()).isEqualTo(3);
    }

    @Test void recreatedInstanceIsRejectedBeforeAnyWakeupOrTask(){
        var runtime=scopedRuntime();doThrow(new com.eap.platform.common.ConflictException("identity changed")).when(connections).checkIdentity(runtime);
        assertThatThrownBy(()->client.invoke(runtime,toolPayload(),Instant.now().plusSeconds(30))).isInstanceOf(com.eap.platform.common.ConflictException.class);
        verify(substrateClient,never()).resume(any());assertThat(taskCalls.get()).isZero();
    }
    private AgentRuntimeInstance scopedRuntime() {
        var runtime = runtime("substrate://eap-demo/session-1");
        when(runtime.getImportedActorUid()).thenReturn("fixture");
        when(runtime.hasBoundIdentity()).thenReturn(true);
        when(connections.executable(runtime)).thenReturn(true);
        when(connections.dispatchToken(runtime)).thenReturn("scoped-test");
        when(substrateClient.inspect(any())).thenReturn(new SubstrateClient.ActorInventory("eap-demo", "session-1", "fixture", "template", "SUSPENDED"));
        when(substrateClient.suspend(any())).thenReturn(new SubstrateClient.ActorState("SUSPENDED"));
        return runtime;
    }

    @Test void managedRunnerRequiresIsolationBeforeTaskDispatchAndRestoresSleepingInstance() {
        var runtime=scopedRuntime();
        var db=mock(org.springframework.jdbc.core.JdbcTemplate.class);
        when(db.queryForObject(anyString(),eq(Long.class),any(),any(),any())).thenReturn(1L);
        org.springframework.test.util.ReflectionTestUtils.setField(client,"db",db);
        var prepared=new java.util.concurrent.atomic.AtomicInteger();
        var rejected=client.invoke(runtime,Map.of("taskKey","workflow:1"),Instant.now().plusSeconds(30),prepared::incrementAndGet);
        assertThat(rejected.status()).isEqualTo("FAILED");assertThat(rejected.reason()).contains("隔离").contains("未派发");
        assertThat(taskCalls).hasValue(0);assertThat(prepared).hasValue(0);
        verify(substrateClient, never()).suspend(any());
        toolState.set("{\"conversationMode\":\"EXPLICIT_V1\"}");
        assertThat(client.invoke(runtime,Map.of("taskKey","workflow:1"),Instant.now().plusSeconds(30),prepared::incrementAndGet).status()).isEqualTo("SUCCEEDED");
        assertThat(taskCalls).hasValue(1);assertThat(prepared).hasValue(1);
    }
    private Map<String, Object> toolPayload() { return Map.of("taskKey", "tool-test", "context", Map.of("eapTool", Map.of("name", "calculate_sum"))); }

    private static AgentRuntimeInstance runtime(String endpoint) {
        AgentRuntimeInstance runtime = mock(AgentRuntimeInstance.class);
        when(runtime.getProtocol()).thenReturn(SubstrateActorRef.PROTOCOL);
        when(runtime.getEndpoint()).thenReturn(endpoint);
        return runtime;
    }
}
