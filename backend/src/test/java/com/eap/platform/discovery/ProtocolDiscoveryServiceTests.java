package com.eap.platform.discovery;

import com.eap.platform.common.BusinessException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class ProtocolDiscoveryServiceTests {
    @Mock
    private AgentDiscoveryService discoveryService;

    private HttpServer server;
    private ProtocolDiscoveryProperties properties;
    private ProtocolDiscoveryService service;

    @BeforeEach
    void setUp() throws IOException {
        properties = new ProtocolDiscoveryProperties();
        properties.setAllowPrivateNetworks(true);
        properties.setConnectTimeoutMs(1_000);
        properties.setReadTimeoutMs(2_000);
        properties.setMaxResponseBytes(32_768);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        service = new ProtocolDiscoveryService(properties, discoveryService, new ObjectMapper());
    }

    @AfterEach
    void tearDown() {
        if (server != null) server.stop(0);
    }

    @Test
    void discoversA2aAgentCardAndRegistersEndpoint() {
        server.createContext("/.well-known/agent-card.json", exchange -> respond(exchange, 200, """
                {"name":"Support Agent","description":"incident triage","version":"1.2.0",
                 "url":"http://127.0.0.1/a2a","protocolVersion":"0.3.0",
                 "capabilities":{"streaming":true},"skills":[{"id":"triage","name":"Incident triage"}]}
                """));
        server.start();

        var response = service.probe(new AgentDiscoveryDtos.ProtocolProbeRequest(
                baseUrl(), ProtocolDiscoveryType.A2A));

        assertThat(response.documentsFetched()).isEqualTo(1);
        assertThat(response.a2aAgentsDiscovered()).isEqualTo(1);
        assertThat(response.mcpServersDiscovered()).isZero();
        verify(discoveryService).registerEndpoint(any());
        verify(discoveryService).ingestEndpoint(any());
    }

    @Test
    void discoversMcpRegistryServersWithoutExecutingPackages() {
        server.createContext("/v0.1/servers", exchange -> respond(exchange, 200, """
                {"servers":[{"server":{"name":"io.example/filesystem","title":"Filesystem",
                 "description":"file access","version":"1.0.0","packages":[
                 {"registryType":"npm","identifier":"@example/filesystem","version":"1.0.0",
                 "transport":{"type":"stdio"}}]}}],"metadata":{"nextCursor":""}}
                """));
        server.start();

        var response = service.probe(new AgentDiscoveryDtos.ProtocolProbeRequest(
                baseUrl(), ProtocolDiscoveryType.MCP_REGISTRY));

        assertThat(response.documentsFetched()).isEqualTo(1);
        assertThat(response.mcpServersDiscovered()).isEqualTo(1);
        assertThat(response.a2aAgentsDiscovered()).isZero();
        verify(discoveryService).registerEndpoint(any());
        verify(discoveryService).ingestEndpoint(any());
    }

    @Test
    void rejectsPrivateTargetsByDefault() {
        properties.setAllowPrivateNetworks(false);

        var restricted = new ProtocolDiscoveryService(properties, discoveryService, new ObjectMapper());

        assertThatThrownBy(() -> restricted.probe(new AgentDiscoveryDtos.ProtocolProbeRequest(
                "http://127.0.0.1:8080", ProtocolDiscoveryType.AUTO)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("拒绝私有网络");
    }

    private String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] payload = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, payload.length);
        try (var output = exchange.getResponseBody()) {
            output.write(payload);
        }
    }
}
