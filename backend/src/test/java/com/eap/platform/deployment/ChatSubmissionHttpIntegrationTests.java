package com.eap.platform.deployment;

import com.eap.platform.observability.ObservationCoverageService;
import com.eap.platform.registry.AgentRuntimeInstance;
import com.eap.platform.registry.AgentRuntimeInstanceRepository;
import com.eap.platform.substrate.SubstrateClient;
import com.eap.platform.substrate.SubstrateProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {"spring.flyway.enabled=true", "eap.security.mode=JWT",
        "eap.security.jwk-set-uri=https://idp.invalid.example/jwks", "eap.substrate.deploy-worker-enabled=false"})
@ActiveProfiles("test")
@AutoConfigureMockMvc
class ChatSubmissionHttpIntegrationTests {
    static final String PATH = "/api/agents/7/substrate/instances/synthetic/chat";
    @Autowired MockMvc api;
    @Autowired SubstrateProperties properties;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate db;
    @Autowired SubstrateDeploymentService deployments;
    @Autowired org.springframework.transaction.PlatformTransactionManager manager;
    @MockitoBean SubstrateClient client;
    @MockitoBean AgentRuntimeInstanceRepository runtimes;
    @MockitoBean ObservationCoverageService coverage;
    final AtomicInteger calls = new AtomicInteger();
    final AtomicInteger guardedCalls = new AtomicInteger();
    final CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
    HttpServer actor;
    ExecutorService threads;
    boolean isolated = true, dropResponse, hold;
    String key;

    @BeforeEach void start() throws Exception {
        key = UUID.randomUUID().toString();
        actor = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        threads = Executors.newCachedThreadPool(); actor.setExecutor(threads);
        actor.createContext("/eap/state", e -> {
            byte[] body = (isolated ? "{\"conversationMode\":\"EXPLICIT_V1\"}" : "{}").getBytes(StandardCharsets.UTF_8);
            e.sendResponseHeaders(200, body.length); e.getResponseBody().write(body); e.close();
        });
        actor.createContext("/chat", e -> {
            e.getRequestBody().readAllBytes(); calls.incrementAndGet(); entered.countDown();
            if (db.queryForObject("select count(*) from runtime_work_claims where actor_key='eap-agents/synthetic' and work_kind='CHAT' and state='ACTIVE'", Long.class) > 0) guardedCalls.incrementAndGet();
            if (hold) try { if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("fixture wait"); }
                catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
            if (!dropResponse) {
                byte[] body = "{\"reply\":\"synthetic-private-answer\",\"turns\":1}".getBytes(StandardCharsets.UTF_8);
                e.sendResponseHeaders(200, body.length); e.getResponseBody().write(body);
            }
            e.close();
        });
        actor.start(); properties.setRouterUrl("http://127.0.0.1:" + actor.getAddress().getPort());
        var runtime = mock(AgentRuntimeInstance.class);
        when(runtime.getAgentId()).thenReturn(7L); when(runtime.getProtocol()).thenReturn("SUBSTRATE");
        when(runtime.getInstanceKey()).thenReturn("eap-agents/synthetic");
        when(runtime.getEndpoint()).thenReturn("substrate://eap-agents/synthetic");
        when(runtimes.findByInstanceKey("eap-agents/synthetic")).thenReturn(Optional.of(runtime));
    }
    @AfterEach void stop() { release.countDown(); actor.stop(0); threads.shutdownNow(); }

    // Also run unchanged against the original source as a negative review probe.
    @Test void lostConsoleResponseAndOriginalKeyReplayDispatchOnlyOnce() throws Exception {
        send("synthetic-private-question"); // Browser loses this response.
        send("synthetic-private-question");
        assertThat(calls.get()).isEqualTo(1);
        assertThat(guardedCalls.get()).isEqualTo(1);
    }

    @Test void receiptIsDurableMetadataAndNeverReplaysConversationContent() throws Exception {
        var first = send("synthetic-private-question");
        assertThat(first.path("reply").asText()).isEqualTo("synthetic-private-answer");
        assertThat(first.path("status").asText()).isEqualTo("SUCCEEDED");
        var query = api.perform(get(PATH + "/" + key).with(actor("owner", "OPERATOR")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(query).contains("SUCCEEDED").doesNotContain("synthetic-private");
        assertThat(send("synthetic-private-question").path("reply").isNull()).isTrue();
        assertThat(mapper.writeValueAsString(db.queryForMap("select * from eap_chat_submissions where request_key=?", key)))
                .doesNotContain("synthetic-private", "\"owner\"");
        assertThat(calls.get()).isEqualTo(1);
    }

    @Test void changedMessageOrHistoryOrTargetCannotReuseAnAdmittedKey() throws Exception {
        send("original");
        api.perform(post(PATH).with(actor("owner", "OPERATOR")).contentType(MediaType.APPLICATION_JSON)
                .content(body("changed"))).andExpect(status().isConflict());
        var changed = Map.of("requestKey", key, "message", "original", "history", List.of(
                Map.of("role", "user", "content", "extra"), Map.of("role", "assistant", "content", "answer")));
        api.perform(post(PATH).with(actor("owner", "OPERATOR")).contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(changed))).andExpect(status().isConflict());
        api.perform(post(PATH.replace("synthetic", "other")).with(actor("owner", "OPERATOR"))
                .contentType(MediaType.APPLICATION_JSON).content(body("original"))).andExpect(status().isBadRequest());
        assertThat(calls.get()).isEqualTo(1);
    }

    @Test void missingKeyIsRejectedBeforeActorWakeup() throws Exception {
        api.perform(post(PATH).with(actor("owner", "OPERATOR")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"message\":\"question\"}")).andExpect(status().isBadRequest());
        verifyNoInteractions(client); assertThat(calls.get()).isZero();
    }

    @Test void queriesAreOwnerBoundAndUseOperatePermission() throws Exception {
        send("original");
        for (String role : List.of("VIEWER", "GOVERNANCE_ADMIN"))
            api.perform(get(PATH + "/" + key).with(actor("owner", role))).andExpect(status().isForbidden());
        for (String role : List.of("OPERATOR", "PLATFORM_ADMIN"))
            api.perform(get(PATH + "/" + key).with(actor("other", role))).andExpect(status().isBadRequest());
        api.perform(get(PATH + "/" + key)).andExpect(status().isUnauthorized());
        assertThat(calls.get()).isEqualTo(1);
    }

    @Test void concurrentDuplicateReturnsOriginalInFlightReceiptWithoutAnotherCall() throws Exception {
        hold = true;
        Future<?> first = threads.submit(() -> { try { send("original"); } catch (Exception e) { throw new RuntimeException(e); } });
        assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
        assertThat(send("original").path("status").asText()).isEqualTo("DISPATCHED");
        assertThat(calls.get()).isEqualTo(1); release.countDown(); first.get(10, TimeUnit.SECONDS);
    }

    @Test void lostActorResponseRemainsUnknownAndQueriesOrPostsNeverResend() throws Exception {
        dropResponse = true;
        assertThat(send("original").path("status").asText()).isEqualTo("UNKNOWN");
        assertThat(send("original").path("status").asText()).isEqualTo("UNKNOWN");
        api.perform(get(PATH + "/" + key).with(actor("owner", "OPERATOR")))
                .andExpect(jsonPath("$.data.status").value("UNKNOWN"));
        assertThat(calls.get()).isEqualTo(1);
    }

    @Test void preflightFailureIsNotDispatchedAndRemainsTerminalOnReplay() throws Exception {
        isolated = false;
        assertThat(send("original").path("status").asText()).isEqualTo("NOT_DISPATCHED");
        isolated = true;
        assertThat(send("original").path("status").asText()).isEqualTo("NOT_DISPATCHED");
        assertThat(calls.get()).isZero();
    }

    @Test void expiredPreflightCannotDispatchAndReadDoesNotOverclaim() throws Exception {
        doAnswer(call -> {
            db.update("update eap_chat_submissions set dispatch_deadline=? where request_key=?",
                    Timestamp.from(Instant.now().minusSeconds(1)), key);
            api.perform(get(PATH + "/" + key).with(actor("owner", "OPERATOR")))
                    .andExpect(jsonPath("$.data.status").value("UNKNOWN"));
            return null;
        }).when(client).resume(any());
        assertThat(send("original").path("status").asText()).isEqualTo("NOT_DISPATCHED");
        assertThat(calls.get()).isZero();
    }

    @Test void abandonedDispatchSurvivesServiceReconstructionWithoutReplay() throws Exception {
        send("original");
        // Fault fixture: process stopped after dispatch, before committing its successful receipt.
        db.update("update eap_chat_submissions set status='DISPATCHED',result_digest=null,turns=null,dispatch_deadline=? where request_key=?",
                Timestamp.from(Instant.now().minusSeconds(1)), key);
        api.perform(get(PATH + "/" + key).with(actor("owner", "OPERATOR")))
                .andExpect(jsonPath("$.data.status").value("UNKNOWN"));
        var reconstructed = new ChatSubmissionService(db, manager, deployments, mapper);
        assertThat(reconstructed.get(7L, "synthetic", key, "owner").status()).isEqualTo("UNKNOWN");
        assertThat(reconstructed.submit(7L, "synthetic", new SubstrateDeploymentDtos.ChatRequest(key, "original", List.of()), "owner").status()).isEqualTo("UNKNOWN");
        assertThat(calls.get()).isEqualTo(1);
    }

    private com.fasterxml.jackson.databind.JsonNode send(String message) throws Exception {
        String response = api.perform(post(PATH).with(actor("owner", "OPERATOR"))
                .contentType(MediaType.APPLICATION_JSON).content(body(message)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return mapper.readTree(response).path("data");
    }
    private String body(String message) throws Exception {
        return mapper.writeValueAsString(Map.of("requestKey", key, "message", message, "history", List.of()));
    }
    private static RequestPostProcessor actor(String subject, String role) {
        return jwt().jwt(t -> t.subject(subject)).authorities(new SimpleGrantedAuthority("ROLE_" + role));
    }
}
