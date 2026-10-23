package com.eap.platform.control;

import com.eap.platform.agent.*;
import com.eap.platform.registry.*;
import com.eap.platform.common.BusinessException;
import com.fasterxml.jackson.databind.*;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import java.net.InetSocketAddress;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Real HTTP receiver and committed H2 transactions. Private endpoints allowed only in this test context. */
@SpringBootTest(properties = {"spring.flyway.enabled=true", "eap.control.dispatch.enabled=true",
        "eap.control.dispatch.allow-private-networks=true", "eap.control.dispatch.read-timeout-ms=600"})
@ActiveProfiles("test")
@AutoConfigureMockMvc
class ControlTransportIntegrationTests {
    @Autowired AgentControlService controls;
    @Autowired AgentRepository agents;
    @Autowired AgentRegistryService registry;
    @Autowired AgentControlCommandRepository commands;
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    HttpServer server;
    ExecutorService workers;
    Agent agent;
    String runtime;
    volatile int responseCode = 200;
    volatile boolean loseAck;
    volatile long delay;
    final AtomicInteger requests = new AtomicInteger(), effects = new AtomicInteger();
    final Map<String, JsonNode> applied = new ConcurrentHashMap<>();
    final Map<String, JsonNode> receiverState = new ConcurrentHashMap<>();
    final List<String> keys = new CopyOnWriteArrayList<>();

    @BeforeEach void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        workers = Executors.newCachedThreadPool(); server.setExecutor(workers);
        server.createContext("/eap/control", exchange -> {
            requests.incrementAndGet();
            JsonNode body = json.readTree(exchange.getRequestBody());
            String key = exchange.getRequestHeaders().getFirst("Idempotency-Key");
            keys.add(key);
            if (!Objects.equals(key, body.path("commandKey").asText()) ||
                    !Objects.equals(key, exchange.getRequestHeaders().getFirst("X-EAP-Control-Command-Key"))) {
                exchange.sendResponseHeaders(400, -1); exchange.close(); return;
            }
            if (delay > 0) try { Thread.sleep(delay); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            if (responseCode == 200 && applied.putIfAbsent(key, body) == null) {
                effects.incrementAndGet(); receiverState.put(body.path("commandType").asText(), body.path("payload"));
            }
            // A receiver can apply a command and lose the response; retries must reuse its idempotency key.
            if (loseAck) { try { Thread.sleep(900); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } }
            try { exchange.sendResponseHeaders(responseCode, -1); } finally { exchange.close(); }
        }); server.start();
        agent = new Agent(); agent.setCode("CTRL-TEST-" + UUID.randomUUID()); agent.setName("Control transport test");
        agent.setOwnerDepartment("Test"); agent.setOwnerUser("Test"); agent.setCategory("Test"); agent = agents.saveAndFlush(agent);
        runtime = "http-test-" + agent.getId();
        registry.registerRuntime(agent.getId(), new AgentRegistryDtos.RuntimeInstanceRequest(runtime,
                "http://127.0.0.1:" + server.getAddress().getPort(), "HTTP", false));
    }
    @AfterEach void stop() { server.stop(0); workers.shutdownNow(); }
    AgentControlDtos.CommandResponse issue(String type, String payload) {
        return controls.issue(new AgentControlDtos.CommandRequest(agent.getId(), type, payload, "original-operator", runtime));
    }
    @Test void configurationRateAndPolicyReachReceiverAsStructuredPayloads() {
        for (String type : List.of("UPDATE_CONFIG", "SET_RATE_LIMIT", "RELOAD_POLICY")) {
            var result = issue(type, "{\"limit\":7,\"mode\":\"synthetic\"}");
            assertThat(result.status()).isEqualTo(ControlCommandStatus.ACKED);
            assertThat(receiverState.get(type).path("limit").asInt()).isEqualTo(7);
        }
        assertThat(effects.get()).isEqualTo(3);
    }
    @Test void real503RetriesSameCommandThenStopsAfterAck() {
        responseCode = 503; var first = issue("UPDATE_CONFIG", "{\"limit\":8}");
        assertThat(first.status()).isEqualTo(ControlCommandStatus.FAILED); assertThat(first.httpStatus()).isEqualTo(503);
        responseCode = 200; var retried = controls.retry(first.commandKey(), "retry-operator");
        assertThat(retried.status()).isEqualTo(ControlCommandStatus.ACKED); assertThat(retried.attemptCount()).isEqualTo(2);
        assertThat(keys).containsExactly(first.commandKey(), first.commandKey()); assertThat(effects.get()).isEqualTo(1);
        assertThatThrownBy(() -> controls.retry(first.commandKey(), "retry-operator")).isInstanceOf(BusinessException.class);
        assertThat(requests.get()).isEqualTo(2);
    }
    @Test void lostAckRetryPreservesIdempotencyAndAppliesOnce() {
        loseAck = true; var first = issue("SET_RATE_LIMIT", "{\"requestsPerMinute\":10}");
        assertThat(first.status()).isEqualTo(ControlCommandStatus.FAILED); assertThat(effects.get()).isEqualTo(1);
        loseAck = false; var next = controls.retry(first.commandKey(), "retry-operator");
        assertThat(next.status()).isEqualTo(ControlCommandStatus.ACKED);
        assertThat(keys).containsExactly(first.commandKey(), first.commandKey()); assertThat(effects.get()).isEqualTo(1);
    }
    @Test void realFailureStopsAtMaximumAttempts() {
        responseCode = 503; var first = issue("RUN_HEALTH_CHECK", "{}");
        controls.retry(first.commandKey(), "retry-operator"); controls.retry(first.commandKey(), "retry-operator");
        assertThatThrownBy(() -> controls.retry(first.commandKey(), "retry-operator")).isInstanceOf(BusinessException.class).hasMessageContaining("最大重试次数");
        assertThat(requests.get()).isEqualTo(3);
    }
    @Test void offlineExplicitTargetDoesNotSendOrFallBack() {
        registry.registerRuntime(agent.getId(), new AgentRegistryDtos.RuntimeInstanceRequest(runtime + "-other",
                "http://127.0.0.1:" + server.getAddress().getPort(), "HTTP", false));
        registry.heartbeat(runtime, new AgentRegistryDtos.HeartbeatRequest(RuntimeStatus.OFFLINE, 0));
        assertThat(issue("PAUSE", "{}").status()).isEqualTo(ControlCommandStatus.FAILED);
        assertThat(requests.get()).isZero();
    }
    @Test void retiredAgentCannotRetryPreviouslyFailedCommand() {
        responseCode = 503; var first = issue("PAUSE", "{}");
        agent.setLifecycleStatus(AgentLifecycleStatus.RETIRED); agents.saveAndFlush(agent); responseCode = 200;
        assertThatThrownBy(() -> controls.retry(first.commandKey(), "retry-operator")).isInstanceOf(BusinessException.class).hasMessageContaining("退役");
        assertThat(requests.get()).isEqualTo(1);
    }
    @Test void concurrentRetriesDispatchOnlyOneSuccessfulAttempt() throws Exception {
        responseCode = 503; var first = issue("UPDATE_CONFIG", "{}"); responseCode = 200; delay = 180;
        var gate = new CyclicBarrier(4);
        try (var callers = Executors.newFixedThreadPool(4)) {
            var futures = new ArrayList<Future<Boolean>>();
            for (int i = 0; i < 4; i++) futures.add(callers.submit(() -> {
                gate.await(); try { return controls.retry(first.commandKey(), "retry-operator").status() == ControlCommandStatus.ACKED; }
                catch (BusinessException e) { return false; }
            }));
            int successes = 0; for (var f : futures) if (f.get(10, TimeUnit.SECONDS)) successes++;
            assertThat(successes).isEqualTo(1);
        }
        assertThat(requests.get()).isEqualTo(2);
        assertThat(commands.findByCommandKey(first.commandKey()).orElseThrow().attemptCount).isEqualTo(2);
    }
    @Test void retryAuditNamesAuthenticatedRetryOperator() throws Exception {
        responseCode = 503; var first = issue("PAUSE", "{}"); responseCode = 200;
        mvc.perform(post("/api/control/commands/{key}/retry", first.commandKey()).with(user("retry-operator")))
                .andExpect(status().isOk());
        assertThat(jdbc.queryForObject("select actor from audit_logs where resource_type='AGENT' and resource_id=? order by id desc limit 1",
                String.class, String.valueOf(agent.getId()))).isEqualTo("retry-operator");
    }
}
