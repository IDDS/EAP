package com.eap.platform.registry;

import com.eap.platform.agent.*;
import com.eap.platform.discovery.DiscoveryEndpointRepository;
import com.eap.platform.gateway.OpenAiUpstreamClient;
import com.eap.platform.telemetry.ModelInvocationEventRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest(properties={"spring.flyway.enabled=true","eap.gateway.enabled=true","eap.gateway.upstream-base-url=http://synthetic.invalid"})
@ActiveProfiles("test")
@Transactional
class RuntimeGatewayIntegrationTests {
    @Autowired RuntimeAccessController controller;
    @Autowired AgentService agents;
    @Autowired DiscoveryEndpointRepository endpoints;
    @Autowired ModelInvocationEventRepository models;
    @Autowired ObjectMapper json;
    @MockitoBean RuntimeConnectionService connections;
    @MockitoBean OpenAiUpstreamClient upstream;

    @Test void firstScopedModelRegistersEndpointBeforeProviderCallAndPersistsGovernedUsage()throws Exception{
        var agent=agents.create(new AgentDtos.AgentRequest("SCOPED-"+UUID.randomUUID(),"Scoped acceptance","","Test","Owner","Test",RiskLevel.LOW));
        String endpoint="runtime-acceptance-"+agent.id();
        var access=new RuntimeConnectionService.Access("eap-agents/acceptance",agent.id(),"generation",endpoint);
        when(connections.authenticate(access.instanceKey(),"synthetic-scoped-token")).thenReturn(access);
        when(connections.resolveTrace(eq(access),anyString())).thenAnswer(i->RuntimeScopedTelemetry.trace(access.instanceKey(),i.getArgument(1)));
        assertThat(endpoints.findByEndpointKey(endpoint)).isEmpty();
        var output=json.readTree("{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"synthetic result\"}}],\"usage\":{\"prompt_tokens\":2,\"completion_tokens\":1,\"total_tokens\":3}}");
        when(upstream.chatCompletions(any())).thenAnswer(invocation->{
            assertThat(endpoints.findByEndpointKey(endpoint)).isPresent();
            return ResponseEntity.ok(output);
        });
        var request=new MockHttpServletRequest();request.addHeader("X-EAP-Runtime-Key",access.instanceKey());request.addHeader("X-EAP-Runtime-Token","synthetic-scoped-token");
        var response=controller.chat(json.readTree("{\"model\":\"synthetic-model\",\"messages\":[{\"role\":\"user\",\"content\":\"synthetic request\"}]}"),request);
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(models.findAll().stream().filter(e->endpoint.equals(e.getEndpointKey())).count()).isEqualTo(1);
        assertThat(response.getHeaders().getFirst("X-EAP-Governance-Decision")).isNotBlank();
        verify(upstream,times(1)).chatCompletions(any());
        verify(connections).recordGateway(eq(access),anyString());
    }
}
