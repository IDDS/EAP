package com.eap.platform.gateway;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OpenAiUpstreamClientTests {
    private HttpServer server;
    private ExecutorService executor;
    private OpenAiUpstreamClient client;
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        executor = Executors.newCachedThreadPool();
        server.setExecutor(executor);
        server.start();
        var properties = new GatewayProperties();
        properties.setUpstreamBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        properties.setMaxResponseBytes(512);
        properties.setReadTimeoutMs(1000);
        client = new OpenAiUpstreamClient(properties, RestClient.builder(), mapper);
    }

    @AfterEach
    void tearDown() throws Exception {
        server.stop(0);
        executor.shutdownNow();
        executor.awaitTermination(5, TimeUnit.SECONDS);
    }

    @Test
    void preservesSuccessfulSseStatusHeadersAndUtf8Body() {
        String body = "data: {\"choices\":[{\"delta\":{\"content\":\"你好\"}}]}\n\ndata: [DONE]\n\n";
        respond("/v1/chat/completions", 201, "text/event-stream", body);
        var response = client.chatCompletionsStream(mapper.createObjectNode().put("stream", true));
        assertThat(response.getStatusCode().value()).isEqualTo(201);
        assertThat(response.getHeaders().getFirst("X-Upstream-Test")).isEqualTo("preserved");
        assertThat(response.getBody()).isEqualTo(body);
    }

    @Test
    void rejectsChunkedOversizeBeforeUpstreamFinishes() throws Exception {
        var release = new CountDownLatch(1);
        server.createContext("/v1/chat/completions", exchange -> {
            try (exchange) {
                exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
                exchange.sendResponseHeaders(200, 0);
                exchange.getResponseBody().write("x".repeat(513).getBytes(StandardCharsets.UTF_8));
                exchange.getResponseBody().flush();
                try { release.await(5, TimeUnit.SECONDS); }
                catch (InterruptedException exception) { Thread.currentThread().interrupt(); }
            }
        });
        try {
            var result = executor.submit(() -> client.chatCompletionsStream(mapper.createObjectNode().put("stream", true)));
            assertThatThrownBy(() -> result.get(2, TimeUnit.SECONDS))
                    .hasCauseInstanceOf(GatewayException.class)
                    .satisfies(error -> {
                        var gateway = (GatewayException) error.getCause();
                        assertThat(gateway.getStatus()).isEqualTo(HttpStatus.BAD_GATEWAY);
                        assertThat(gateway.getCode()).isEqualTo("stream_response_too_large");
                        assertThat(gateway.getMessage()).doesNotContain("xxx");
                    });
        } finally {
            release.countDown();
        }
    }

    @Test
    void appliesBoundToJsonAndErrorResponsesToo() {
        respond("/v1/chat/completions", 200, "application/json", "{\"content\":\"" + "x".repeat(1024) + "\"}");
        respond("/v1/models", 503, "application/json", "{\"error\":\"" + "x".repeat(1024) + "\"}");
        assertThatThrownBy(() -> client.chatCompletions(mapper.createObjectNode())).isInstanceOf(GatewayException.class);
        assertThatThrownBy(client::models).isInstanceOf(GatewayException.class);
    }

    @Test
    void preservesNormalJsonAndBoundedErrorStatus() {
        respond("/v1/models", 200, "application/json", "{\"data\":[]}");
        respond("/v1/chat/completions", 429, "application/json", "{\"error\":\"rate limit\"}");
        assertThat(client.models().getBody().path("data").isArray()).isTrue();
        assertThatThrownBy(() -> client.chatCompletions(mapper.createObjectNode()))
                .isInstanceOf(RestClientResponseException.class)
                .satisfies(error -> assertThat(((RestClientResponseException) error).getStatusCode().value()).isEqualTo(429));
    }

    @Test
    void interruptedResponseDoesNotReturnPartialSse() {
        server.createContext("/v1/chat/completions", exchange -> {
            try (exchange) {
                exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
                exchange.sendResponseHeaders(200, 128);
                exchange.getResponseBody().write("data: partial\n\n".getBytes(StandardCharsets.UTF_8));
                exchange.getResponseBody().flush();
            }
        });
        assertThatThrownBy(() -> client.chatCompletionsStream(mapper.createObjectNode().put("stream", true)))
                .isInstanceOfAny(GatewayException.class, RestClientException.class);
    }

    private void respond(String path, int status, String contentType, String content) {
        server.createContext(path, exchange -> {
            try (exchange) {
                byte[] body = content.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", contentType);
                exchange.getResponseHeaders().set("X-Upstream-Test", "preserved");
                exchange.sendResponseHeaders(status, body.length);
                exchange.getResponseBody().write(body);
            }
        });
    }
}
