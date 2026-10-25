package com.eap.platform.discovery;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class EbpfObservationContractTests {
    private final ObjectMapper json = new ObjectMapper();
    private AgentDiscoveryService discovery;
    private HostObservationService service;

    @BeforeEach void setup() {
        discovery = mock(AgentDiscoveryService.class);
        when(discovery.ingestEndpointWithReceipt(any())).thenReturn(
                new AgentDiscoveryService.EndpointIngestionReceipt(null, 1, 1, 0, 0));
        var properties = new HostObservationProperties();
        properties.setEnabled(true);
        service = new HostObservationService(properties, discovery);
    }

    private AgentDiscoveryDtos.EndpointEvidenceRequest ingest(String version, String fields) throws Exception {
        var flow = json.readValue("{\"processName\":\"agent.py\",\"processId\":42,\"remoteAddress\":\"203.0.113.10\",\"remotePort\":443" + fields + "}",
                HostObservationService.HostObservationDtos.HostNetworkObservation.class);
        service.ingest(new HostObservationService.HostObservationDtos.HostObservationRequest(
                HostObservationSensorType.EBPF, "fixture-host", "fixture", null, "Linux", version, List.of(flow)));
        var request = ArgumentCaptor.forClass(AgentDiscoveryDtos.EndpointObservationRequest.class);
        verify(discovery).ingestEndpointWithReceipt(request.capture());
        return request.getValue().observations().getFirst();
    }

    @Test void legacyConnectCollectorDoesNotClaimMeasuredTraffic() throws Exception {
        var item = ingest("eap-ebpf-bpftrace/1.0.0", ",\"transport\":\"TCP\",\"protocolHint\":\"HTTPS\",\"bytesSent\":0,\"bytesReceived\":0");
        assertThat(item.evidenceType()).isEqualTo("EBPF_CONNECT_ATTEMPT");
        assertThat(item.evidence()).containsEntry("observationKind", "CONNECT_ATTEMPT")
                .containsEntry("connectionOutcome", "UNCONFIRMED")
                .containsEntry("byteMeasurement", "UNAVAILABLE")
                .doesNotContainKeys("bytesSent", "bytesReceived", "transport", "protocolHint", "tlsServerName");
    }

    @Test void omittedByteMeasurementsRemainAbsent() throws Exception {
        Map<String, Object> evidence = ingest("third-party/1", "").evidence();
        assertThat(evidence).doesNotContainKeys("bytesSent", "bytesReceived");
    }

    @Test void measuredZeroAndPositiveBytesRemainCompatible() throws Exception {
        var item = ingest("third-party/1", ",\"transport\":\"TCP\",\"bytesSent\":0,\"bytesReceived\":24");
        assertThat(item.evidence()).containsEntry("bytesSent", 0L).containsEntry("bytesReceived", 24L)
                .containsEntry("transport", "TCP");
        assertThat(item.evidenceType()).isEqualTo("EBPF_NETWORK_FLOW");
    }

    @Test void explicitConnectAttemptCannotClaimMeasuredFields() throws Exception {
        var item = ingest("eap-ebpf-bpftrace/1.1.0", ",\"observationKind\":\"CONNECT_ATTEMPT\",\"transport\":\"TCP\",\"protocolHint\":\"HTTPS\",\"tlsServerName\":\"invented.example\",\"bytesSent\":24,\"bytesReceived\":42");
        assertThat(item.evidenceType()).isEqualTo("EBPF_CONNECT_ATTEMPT");
        assertThat(item.evidence()).containsEntry("connectionOutcome", "UNCONFIRMED")
                .doesNotContainKeys("bytesSent", "bytesReceived", "protocolHint", "transport", "tlsServerName", "localPort");
    }

    @Test void unsupportedObservationKindsAreRejected() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> ingest("fixture/1", ",\"observationKind\":\"TOOL_SUCCEEDED\""))
                .isInstanceOf(com.fasterxml.jackson.databind.exc.InvalidFormatException.class);
        verifyNoInteractions(discovery);
    }
}
