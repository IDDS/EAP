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
import static org.mockito.Mockito.*;

class WorkflowTaskClientSubstrateTests {
    private final ObjectMapper json = new ObjectMapper();
    private final AtomicReference<String> routedActor = new AtomicReference<>();
    private final AtomicReference<String> path = new AtomicReference<>();
    private final SubstrateClient substrateClient = mock(SubstrateClient.class);
    private HttpServer router;
    private WorkflowTaskClient client;

    @BeforeEach
    void setUp() throws Exception {
        router = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        router.createContext("/", exchange -> {
            routedActor.set(exchange.getRequestHeaders().getFirst("ate-target-actor"));
            path.set(exchange.getRequestURI().getPath());
            JsonNode request = json.readTree(exchange.getRequestBody());
            byte[] body = json.writeValueAsBytes(Map.of("taskKey", request.path("taskKey").asText(), "status", "SUCCEEDED"));
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
        client = new WorkflowTaskClient(properties, json, substrate, substrateClient);
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

    private static AgentRuntimeInstance runtime(String endpoint) {
        AgentRuntimeInstance runtime = mock(AgentRuntimeInstance.class);
        when(runtime.getProtocol()).thenReturn(SubstrateActorRef.PROTOCOL);
        when(runtime.getEndpoint()).thenReturn(endpoint);
        return runtime;
    }
}
