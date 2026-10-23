package com.eap.platform.registry;

import com.eap.platform.common.BusinessException;
import com.eap.platform.config.UnauthorizedException;
import com.eap.platform.gateway.*;
import com.eap.platform.observability.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class RuntimeAccessControllerTests {
    final RuntimeConnectionService connections=mock(RuntimeConnectionService.class);
    final OpenAiCompatibleGatewayService gateway=mock(OpenAiCompatibleGatewayService.class);
    final OtlpTraceIngestionService traces=mock(OtlpTraceIngestionService.class);
    final DiagnosticSamplingService diagnostics=mock(DiagnosticSamplingService.class);
    final RuntimeAccessController controller=new RuntimeAccessController(connections,gateway,traces,mock(com.eap.platform.discovery.AgentDiscoveryService.class),diagnostics);
    final RuntimeConnectionService.Access access=new RuntimeConnectionService.Access("eap-agents/external",71L,"generation","runtime-901");
    final ObjectMapper json=new ObjectMapper();
    MockHttpServletRequest request(){
        var request=new MockHttpServletRequest();request.addHeader("X-EAP-Runtime-Key",access.instanceKey());request.addHeader("Authorization","Bearer synthetic-scoped-credential");
        when(connections.authenticate(access.instanceKey(),"synthetic-scoped-credential")).thenReturn(access);when(connections.resolveTrace(eq(access),anyString())).thenAnswer(i->RuntimeScopedTelemetry.trace(access.instanceKey(),i.getArgument(1)));return request;
    }
    @Test void modelAttributionComesOnlyFromAuthenticatedInstanceAndTraceIsNamespaced(){
        var request=request();request.addHeader("X-EAP-Trace-Id","11111111111111111111111111111111");
        var body=json.createObjectNode();when(gateway.chatCompletions(any(),any(),any(),any(),any(),any(),any())).thenReturn(ResponseEntity.ok(json.createObjectNode()));
        controller.chat(body,request);
        String trace=RuntimeScopedTelemetry.trace(access.instanceKey(),"11111111111111111111111111111111");
        verify(gateway).chatCompletions(eq(body),eq(71L),eq("runtime-901"),isNull(),eq(trace),any(),isNull());
        verify(connections).recordGateway(access,trace);
    }
    @Test void foreignAgentEndpointTaskAndDecisionOverridesNeverReachGateway(){
        for(String header:List.of("X-EAP-Agent-Id","X-EAP-Endpoint-Key","X-EAP-Task-Id","X-EAP-Governance-Decision-Key")){
            var request=request();request.addHeader(header,"999");
            assertThatThrownBy(()->controller.chat(json.createObjectNode(),request)).isInstanceOf(BusinessException.class);
        }
        verifyNoInteractions(gateway);
    }
    @Test void missingCredentialOrForeignOtlpIdentityIsRejectedBeforeIngestion()throws Exception{
        assertThatThrownBy(()->controller.ingest(json.createObjectNode(),new MockHttpServletRequest())).isInstanceOf(UnauthorizedException.class);
        var payload=json.readTree("{\"resourceSpans\":[{\"resource\":{\"attributes\":[{\"key\":\"agent.id\",\"value\":{\"intValue\":72}}]},\"scopeSpans\":[]}]}");
        assertThatThrownBy(()->controller.ingest(payload,request())).isInstanceOf(BusinessException.class);
        verifyNoInteractions(traces,diagnostics);
    }
}
