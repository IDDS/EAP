package com.eap.platform.observability;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {"spring.flyway.enabled=true", "eap.security.mode=JWT",
        "eap.security.issuer-uri=https://idp.invalid.example/realm/eap", "eap.security.jwk-set-uri=https://idp.invalid.example/jwks",
        "eap.substrate.deploy-worker-enabled=false", "eap.management.connectors.scheduler-enabled=false"})
@ActiveProfiles("test")
@AutoConfigureMockMvc
class TraceMetadataAccessIntegrationTests {
    @Autowired MockMvc mvc;
    @Autowired TraceSpanObservationRepository spans;
    @Autowired ToolInvocationEventRepository tools;
    @ParameterizedTest
    @ValueSource(strings = {"VIEWER", "OPERATOR", "GOVERNANCE_ADMIN", "PLATFORM_ADMIN"})
    void everyPublicReadPathProjectsLegacyBodyAttributes(String role) throws Exception {
        String trace = "security-legacy-" + role;
        var span = new TraceSpanObservation(); span.traceId = trace; span.spanId = "one";
        span.endpointKey = "review-security"; span.spanName = "mcp call"; span.spanKind = TraceSpanKind.MCP;
        span.attributesJson = "{\"mcp.request\":\"PRIVATE_LEGACY_BODY\",\"tool.name\":\"search\"}";
        spans.saveAndFlush(span);
        var tool = new ToolInvocationEvent(); tool.invocationKey = trace; tool.traceId = trace;
        tool.endpointKey = "review-security"; tool.toolName = "search"; tool.toolKind = ToolInvocationKind.MCP;
        tool.observationSource = ToolInvocationSource.OTLP; tool.status = ToolInvocationStatus.SUCCEEDED;
        tool.attributesJson = span.attributesJson; tools.saveAndFlush(tool);
        try {
            for (String route : new String[]{"/api/observability/otlp/spans", "/api/observability/otlp/spans/page",
                    "/api/observability/tool-invocations", "/api/observability/tool-invocations/page"}) {
                var response = mvc.perform(get(route).param("traceId", trace)
                                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_" + role))))
                        .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
                assertThat(response).contains("search").doesNotContain("PRIVATE_LEGACY_BODY", "mcp.request");
            }
        } finally { tools.delete(tool); spans.delete(span); }
    }
}
