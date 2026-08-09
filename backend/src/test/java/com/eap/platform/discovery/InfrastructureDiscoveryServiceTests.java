package com.eap.platform.discovery;

import com.eap.platform.agent.RiskLevel;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class InfrastructureDiscoveryServiceTests {
    @Mock
    private AgentDiscoveryService discoveryService;

    private ObjectMapper objectMapper;
    private KubernetesDiscoveryService kubernetesService;
    private EbpfDiscoveryService ebpfService;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        KubernetesDiscoveryProperties kubernetesProperties = new KubernetesDiscoveryProperties();
        kubernetesProperties.setEnabled(true);
        kubernetesService = new KubernetesDiscoveryService(kubernetesProperties, discoveryService, objectMapper);
        EbpfDiscoveryProperties ebpfProperties = new EbpfDiscoveryProperties();
        ebpfProperties.setEnabled(true);
        ebpfService = new EbpfDiscoveryService(ebpfProperties, discoveryService);
    }

    @Test
    void normalizesKubernetesWatchAgentAndMcpContainersWithoutEnvValues() throws Exception {
        var payload = objectMapper.readTree("""
                {"type":"ADDED","clusterName":"prod-cluster","object":{
                  "kind":"Deployment","metadata":{"name":"ops-agent","namespace":"ops","uid":"uid-1",
                    "labels":{"eap.ai/framework":"LangGraph"}},
                  "spec":{"template":{"spec":{"containers":[
                    {"name":"agent","image":"registry.example/ops-agent:1.4.0",
                     "env":[{"name":"OPENAI_API_KEY","value":"should-not-be-stored"}],"ports":[{"name":"http","containerPort":9000}]},
                    {"name":"mcp-server","image":"registry.example/mcp-files:2.0.0"}
                  ]}}}}}
                """);

        var response = kubernetesService.ingestWatch(payload);

        assertThat(response.eventType()).isEqualTo("ADDED");
        assertThat(response.containersInspected()).isEqualTo(2);
        assertThat(response.agentsDiscovered()).isEqualTo(1);
        assertThat(response.mcpServersDiscovered()).isEqualTo(1);
        ArgumentCaptor<AgentDiscoveryDtos.EndpointObservationRequest> captor =
                ArgumentCaptor.forClass(AgentDiscoveryDtos.EndpointObservationRequest.class);
        verify(discoveryService).ingestEndpoint(captor.capture());
        assertThat(captor.getValue().observations()).hasSize(2);
        assertThat(captor.getValue().observations().getFirst().sourceType()).isEqualTo(DiscoverySourceType.KUBERNETES_WATCH);
        assertThat(captor.getValue().observations().getFirst().evidence()).doesNotContainValue("should-not-be-stored");
    }

    @Test
    void normalizesAdmissionReviewAndMarksPrivilegedWorkloadHighRisk() throws Exception {
        var payload = objectMapper.readTree("""
                {"request":{"object":{"kind":"Pod","metadata":{"name":"secret-agent","namespace":"prod","uid":"uid-2",
                  "labels":{"ai.eap/agent":"true"}},"spec":{"hostNetwork":true,"containers":[
                  {"name":"agent","image":"ghcr.io/example/agent:latest","securityContext":{"privileged":true}}
                ]}}}}
                """);

        var response = kubernetesService.ingestAdmission(payload);

        assertThat(response.eventType()).isEqualTo("ADMISSION");
        assertThat(response.agentsDiscovered()).isEqualTo(1);
        captorRiskIs(RiskLevel.HIGH);
    }

    @Test
    void normalizesEbpfModelAndMcpFlowsAsPassiveEvidence() {
        var response = ebpfService.ingest(new EbpfDiscoveryService.EbpfDiscoveryDtos.EbpfObservationRequest(
                "host-01", "worker-01", "svc-agent", "Linux", List.of(
                new EbpfDiscoveryService.EbpfDiscoveryDtos.EbpfFlow(
                        "langgraph-worker", "/opt/agent/bin/worker", "10.0.0.2", 45000,
                        "104.18.1.1", 443, "TCP", "OUTBOUND", "HTTPS", "OpenAI", "gpt-4o", "api.openai.com", 1200, 800),
                new EbpfDiscoveryService.EbpfDiscoveryDtos.EbpfFlow(
                        "mcp-client", "/opt/agent/bin/mcp", "10.0.0.2", 45001,
                        "10.0.0.9", 3000, "TCP", "OUTBOUND", "MCP", null, null, "mcp.internal", 100, 200)
        )));

        assertThat(response.flowsInspected()).isEqualTo(2);
        assertThat(response.agentsDiscovered()).isEqualTo(1);
        assertThat(response.mcpServersDiscovered()).isEqualTo(1);
        verify(discoveryService).ingestEndpoint(any());
    }

    private void captorRiskIs(RiskLevel expected) {
        ArgumentCaptor<AgentDiscoveryDtos.EndpointObservationRequest> captor =
                ArgumentCaptor.forClass(AgentDiscoveryDtos.EndpointObservationRequest.class);
        verify(discoveryService).ingestEndpoint(captor.capture());
        assertThat(captor.getValue().observations().getFirst().riskLevel()).isEqualTo(expected);
    }
}
