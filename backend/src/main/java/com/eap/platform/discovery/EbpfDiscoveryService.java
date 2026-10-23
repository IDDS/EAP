package com.eap.platform.discovery;

import com.eap.platform.common.BusinessException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class EbpfDiscoveryService {
    private final EbpfDiscoveryProperties properties;
    private final HostObservationService hostObservationService;

    public EbpfDiscoveryService(
            EbpfDiscoveryProperties properties,
            HostObservationService hostObservationService
    ) {
        this.properties = properties;
        this.hostObservationService = hostObservationService;
    }

    public AgentDiscoveryDtos.EbpfDiscoveryResponse ingest(EbpfDiscoveryDtos.EbpfObservationRequest request) {
        if (!properties.isEnabled()) throw new BusinessException("eBPF 发现连接器未启用");
        int limit = Math.max(1, properties.getMaxFlows());
        List<HostObservationService.HostObservationDtos.HostNetworkObservation> observations = request.flows().stream()
                .limit(limit)
                .map(flow -> new HostObservationService.HostObservationDtos.HostNetworkObservation(
                        flow.processName(), null, flow.executablePath(), null, null, null,
                        flow.localAddress(), flow.localPort(), flow.remoteAddress(), flow.remotePort(),
                        flow.transport(), flow.direction(), flow.protocolHint(), flow.provider(), flow.modelName(),
                        flow.tlsServerName(), flow.bytesSent(), flow.bytesReceived(), flow.identityKey()
                ))
                .toList();
        AgentDiscoveryDtos.HostObservationResponse response = hostObservationService.ingest(
                new HostObservationService.HostObservationDtos.HostObservationRequest(
                        HostObservationSensorType.EBPF,
                        request.endpointKey(), request.hostName(), request.accountName(), request.operatingSystem(),
                        "ebpf-discovery/0.1", observations
                ));
        return new AgentDiscoveryDtos.EbpfDiscoveryResponse(
                response.endpointKey(), response.observationsInspected(), response.agentsDiscovered(),
                response.mcpServersDiscovered(), response.observationsRecorded(), response.warnings()
        );
    }

    public static final class EbpfDiscoveryDtos {
        private EbpfDiscoveryDtos() { }

        public record EbpfObservationRequest(
                @NotBlank @Size(max = 128) String endpointKey,
                @NotBlank @Size(max = 256) String hostName,
                @Size(max = 256) String accountName,
                @Size(max = 128) String operatingSystem,
                @NotEmpty @Size(max = 1000) List<@Valid EbpfFlow> flows
        ) { }

        public record EbpfFlow(
                @Size(max = 256) String processName,
                @Size(max = 1024) String executablePath,
                @Size(max = 128) String localAddress,
                @Min(0) @Max(65535) int localPort,
                @Size(max = 128) String remoteAddress,
                @Min(0) @Max(65535) int remotePort,
                @Size(max = 32) String transport,
                @Size(max = 32) String direction,
                @Size(max = 64) String protocolHint,
                @Size(max = 128) String provider,
                @Size(max = 128) String modelName,
                @Size(max = 256) String tlsServerName,
                @Min(0) long bytesSent,
                @Min(0) long bytesReceived,
                @Size(max = 256) String identityKey
        ) { }
    }
}
