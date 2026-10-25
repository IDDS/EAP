package com.eap.platform.observability;

import com.eap.platform.agent.AgentDtos;
import com.eap.platform.agent.AgentService;
import com.eap.platform.agent.RiskLevel;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {"spring.flyway.enabled=true", "eap.security.mode=JWT",
        "eap.security.issuer-uri=https://idp.invalid.example/realm/eap",
        "eap.security.jwk-set-uri=https://idp.invalid.example/jwks",
        "eap.substrate.deploy-worker-enabled=false", "eap.management.connectors.scheduler-enabled=false"})
@ActiveProfiles("test")
@AutoConfigureMockMvc
class McpProtocolIngestionIntegrationTests {
    @Autowired OtlpTraceIngestionService ingestion;
    @Autowired AgentService agents;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate db;
    @Autowired MockMvc mvc;

    @ParameterizedTest
    @ValueSource(strings = {"VIEWER", "OPERATOR", "GOVERNANCE_ADMIN", "PLATFORM_ADMIN"})
    void persistsPassiveOutcomesAndExposesUnknownWithoutCreatingEffects(String role) throws Exception {
        String key = UUID.randomUUID().toString();
        long agent = agents.create(new AgentDtos.AgentRequest("MCP-" + key, "MCP fixture", "",
                "Test", "Owner", "Test", RiskLevel.LOW)).id();
        String trace = key.replace("-", "");
        String fixture = System.getenv("EAP_MCP_OTLP_FIXTURE");
        var cases = fixture == null ? json.readTree(getClass().getResourceAsStream("/mcp-protocol-fixture.json"))
                : json.readTree(java.nio.file.Path.of(fixture).toFile());
        long models = count("model_invocation_events"), reservations = count("budget_reservations");
        for (var item : cases) {
            var payload = item.path("otlp").deepCopy();
            var span = (ObjectNode) payload.at("/resourceSpans/0/scopeSpans/0/spans/0");
            span.put("traceId", trace);
            var result = ingestion.ingestScoped(payload, agent, "mcp-fixture-" + key);
            assertThat(result.mcpSpansRecorded()).isEqualTo(1);
            assertThat(result.contentObservationsRecorded()).isZero();
            assertThat(result.invocationsRecorded()).isZero();
        }
        assertThat(db.queryForList("select status from trace_span_observations where trace_id=? order by status",
                String.class, trace)).containsExactly("FAILED", "SUCCEEDED", "UNKNOWN");
        assertThat(db.queryForList("select status from tool_invocation_events where trace_id=? order by status",
                String.class, trace)).containsExactly("FAILED", "SUCCEEDED", "UNKNOWN");
        assertThat(db.queryForList("select distinct agent_id from tool_invocation_events where trace_id=?",
                Long.class, trace)).containsExactly(agent);
        for (String route : new String[]{"/api/observability/otlp/spans/page", "/api/observability/tool-invocations/page"}) {
            var body = mvc.perform(get(route).param("traceId", trace).param("status", "UNKNOWN")
                            .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_" + role))))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            assertThat(body).contains("UNKNOWN", "MCP_PROTOCOL_V1", "UNVERIFIED", "STREAM_CLOSED")
                    .doesNotContain("SUCCEEDED", "FAILED", "PRIVATE_INPUT", "PRIVATE_RESULT");
        }
        assertThat(count("model_invocation_events")).isEqualTo(models);
        assertThat(count("budget_reservations")).isEqualTo(reservations);
    }

    private long count(String table) {
        return db.queryForObject("select count(*) from " + table, Long.class);
    }
}
