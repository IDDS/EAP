package com.eap.platform.discovery;

import com.eap.platform.agent.RiskLevel;
import com.eap.platform.common.BusinessException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class EbpfDiscoveryService {
    private final EbpfDiscoveryProperties properties;
    private final AgentDiscoveryService discoveryService;

    public EbpfDiscoveryService(EbpfDiscoveryProperties properties, AgentDiscoveryService discoveryService) {
        this.properties = properties;
        this.discoveryService = discoveryService;
    }

    @Transactional
    public AgentDiscoveryDtos.EbpfDiscoveryResponse ingest(EbpfDiscoveryDtos.EbpfObservationRequest request) {
        if (!properties.isEnabled()) throw new BusinessException("eBPF 发现连接器未启用");
        discoveryService.registerEndpoint(new AgentDiscoveryDtos.EndpointRegistrationRequest(
                request.endpointKey(), request.hostName(), request.accountName(), request.operatingSystem(), "ebpf-discovery/0.1"
        ));
        List<AgentDiscoveryDtos.EndpointEvidenceRequest> observations = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        int inspected = 0;
        for (EbpfDiscoveryDtos.EbpfFlow flow : request.flows().stream().limit(Math.max(1, properties.getMaxFlows())).toList()) {
            inspected++;
            String signal = String.join(" ", safe(flow.processName()), safe(flow.executablePath()), safe(flow.protocolHint()),
                    safe(flow.provider()), safe(flow.modelName()), safe(flow.tlsServerName())).toLowerCase(Locale.ROOT);
            boolean mcp = signal.contains("mcp") || signal.contains("modelcontextprotocol");
            boolean agent = mcp || hasText(flow.provider()) || hasText(flow.modelName()) || containsAgent(signal);
            if (!agent) continue;
            String process = truncate(flow.processName(), 256, "network-agent");
            String remote = truncate(flow.tlsServerName(), 256, flow.remoteAddress() == null ? "unknown-remote" : flow.remoteAddress());
            String provider = truncate(flow.provider(), 128, inferProvider(remote));
            Map<String, Object> evidence = new LinkedHashMap<>();
            evidence.put("sourceSystem", "EBPF");
            putIfPresent(evidence, "processName", process);
            putIfPresent(evidence, "executablePath", truncate(flow.executablePath(), 1024));
            putIfPresent(evidence, "localAddress", truncate(flow.localAddress(), 128));
            putIfPresent(evidence, "localPort", flow.localPort());
            putIfPresent(evidence, "remoteAddress", truncate(flow.remoteAddress(), 128));
            putIfPresent(evidence, "remotePort", flow.remotePort());
            putIfPresent(evidence, "tlsServerName", remote);
            putIfPresent(evidence, "transport", truncate(flow.transport(), 32));
            putIfPresent(evidence, "direction", truncate(flow.direction(), 32));
            putIfPresent(evidence, "protocolHint", truncate(flow.protocolHint(), 64));
            putIfPresent(evidence, "provider", provider);
            putIfPresent(evidence, "modelName", truncate(flow.modelName(), 128));
            putIfPresent(evidence, "bytesSent", flow.bytesSent());
            putIfPresent(evidence, "bytesReceived", flow.bytesReceived());
            String sourceLocation = "ebpf://" + request.hostName() + "/" + process + "/" + remote;
            observations.add(new AgentDiscoveryDtos.EndpointEvidenceRequest(
                    "ebpf-" + Integer.toUnsignedString((request.endpointKey() + ":" + process + ":" + remote).hashCode(), 36),
                    process,
                    mcp ? "MCP" : provider,
                    mcp ? "MCP Network Client" : "Model Network Client",
                    truncate(flow.modelName(), 64),
                    mcp ? DiscoveryAssetType.MCP_CONFIGURATION : DiscoveryAssetType.AGENT,
                    DiscoverySourceType.EBPF,
                    truncate(sourceLocation, 1024),
                    request.accountName(), process, truncate(flow.executablePath(), 1024),
                    null, null, mcp ? 1 : 0,
                    flow.provider() != null || flow.modelName() != null ? 90 : 76,
                    mcp ? RiskLevel.MEDIUM : RiskLevel.LOW,
                    "EBPF_NETWORK_FLOW", evidence
            ));
        }
        if (!observations.isEmpty()) discoveryService.ingestEndpoint(
                new AgentDiscoveryDtos.EndpointObservationRequest(request.endpointKey(), observations));
        int mcp = (int) observations.stream().filter(item -> item.assetType() == DiscoveryAssetType.MCP_CONFIGURATION).count();
        return new AgentDiscoveryDtos.EbpfDiscoveryResponse(
                request.endpointKey(), inspected, observations.size() - mcp, mcp, observations.size(), List.copyOf(warnings)
        );
    }

    private boolean containsAgent(String signal) {
        return signal.contains("agent") || signal.contains("langchain") || signal.contains("langgraph")
                || signal.contains("crewai") || signal.contains("autogen") || signal.contains("mastra")
                || signal.contains("copilot") || signal.contains("claude") || signal.contains("codex")
                || signal.contains("gemini");
    }

    private String inferProvider(String remote) {
        String value = remote == null ? "Unknown" : remote.toLowerCase(Locale.ROOT);
        if (value.contains("openai")) return "OpenAI";
        if (value.contains("anthropic")) return "Anthropic";
        if (value.contains("azure")) return "Azure AI";
        if (value.contains("google") || value.contains("gemini")) return "Google";
        return "Network";
    }

    private String safe(String value) { return value == null ? "" : value; }

    private boolean hasText(String value) { return value != null && !value.isBlank(); }

    private String truncate(String value, int max) { return truncate(value, max, null); }

    private String truncate(String value, int max, String fallback) {
        if (value == null || value.isBlank()) return fallback;
        return value.length() <= max ? value : value.substring(0, max);
    }

    private void putIfPresent(Map<String, Object> target, String key, Object value) {
        if (value != null && !(value instanceof String string && string.isBlank())) target.put(key, value);
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
                @Min(0) long bytesReceived
        ) { }
    }
}
