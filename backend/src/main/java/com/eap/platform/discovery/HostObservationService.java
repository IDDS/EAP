package com.eap.platform.discovery;

import com.eap.platform.agent.RiskLevel;
import com.eap.platform.common.BusinessException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class HostObservationService {
    private final HostObservationProperties properties;
    private final AgentDiscoveryService discoveryService;

    public HostObservationService(HostObservationProperties properties, AgentDiscoveryService discoveryService) {
        this.properties = properties;
        this.discoveryService = discoveryService;
    }

    @Transactional
    public AgentDiscoveryDtos.HostObservationResponse ingest(HostObservationDtos.HostObservationRequest request) {
        return ingest(request, true);
    }

    @Transactional
    public AgentDiscoveryDtos.HostObservationResponse ingestForEndpoint(
            DiscoveryEndpoint endpoint,
            HostObservationSensorType sensorType,
            String collectorVersion,
            List<HostObservationDtos.HostNetworkObservation> observations
    ) {
        return ingest(new HostObservationDtos.HostObservationRequest(
                sensorType, endpoint.endpointKey, endpoint.hostName, endpoint.accountName,
                endpoint.operatingSystem, collectorVersion, observations
        ), false);
    }

    private AgentDiscoveryDtos.HostObservationResponse ingest(
            HostObservationDtos.HostObservationRequest request,
            boolean registerEndpoint
    ) {
        if (!properties.isEnabled()) throw new BusinessException("主机观测连接器未启用");
        String collectorVersion = firstNonBlank(request.collectorVersion(),
                "host-observer-" + request.sensorType().name().toLowerCase(Locale.ROOT) + "/0.1");
        if (registerEndpoint) {
            discoveryService.registerEndpoint(new AgentDiscoveryDtos.EndpointRegistrationRequest(
                    request.endpointKey(), request.hostName(), request.accountName(), request.operatingSystem(),
                    truncate(collectorVersion, 64)
            ));
        }

        List<AgentDiscoveryDtos.EndpointEvidenceRequest> observations = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        int inspected = 0;
        int limit = Math.max(1, properties.getMaxObservations());
        for (HostObservationDtos.HostNetworkObservation flow : request.observations().stream().limit(limit).toList()) {
            inspected++;
            String signal = String.join(" ", safe(flow.processName()), safe(flow.executablePath()), safe(flow.protocolHint()),
                    safe(flow.provider()), safe(flow.modelName()), safe(flow.tlsServerName()), safe(flow.identityKey()),
                    safe(flow.containerId()), safe(flow.workloadUid())).toLowerCase(Locale.ROOT);
            boolean mcp = signal.contains("mcp") || signal.contains("modelcontextprotocol");
            boolean agent = mcp || hasText(flow.provider()) || hasText(flow.modelName()) || containsAgent(signal);
            if (!agent || ignoredSignal(signal)) continue;

            String process = truncate(flow.processName(), 256, "network-agent");
            String remote = truncate(flow.tlsServerName(), 256,
                    flow.remoteAddress() == null ? "unknown-remote" : flow.remoteAddress());
            String provider = truncate(flow.provider(), 128, inferProvider(remote));
            Map<String, Object> evidence = new LinkedHashMap<>();
            evidence.put("sourceSystem", request.sensorType().name());
            evidence.put("sensorType", request.sensorType().name());
            putIfPresent(evidence, "collectorVersion", truncate(collectorVersion, 64));
            putIfPresent(evidence, "identityKey", truncate(flow.identityKey(), 256));
            putIfPresent(evidence, "workloadUid", truncate(flow.workloadUid(), 256));
            putIfPresent(evidence, "containerId", truncate(flow.containerId(), 256));
            putIfPresent(evidence, "cgroupId", truncate(flow.cgroupId(), 256));
            putIfPresent(evidence, "processId", flow.processId());
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

            DiscoverySourceType sourceType = request.sensorType() == HostObservationSensorType.EBPF
                    ? DiscoverySourceType.EBPF : DiscoverySourceType.HOST_OBSERVER;
            String sourceLocation = "host-observer://" + request.sensorType().name().toLowerCase(Locale.ROOT)
                    + "/" + request.hostName() + "/" + process + "/" + remote;
            observations.add(new AgentDiscoveryDtos.EndpointEvidenceRequest(
                    fingerprint(request.endpointKey(), request.sensorType(), process, remote, flow.processId(), flow.containerId()),
                    process,
                    mcp ? "MCP" : provider,
                    mcp ? "MCP Network Client" : "Model Network Client",
                    truncate(flow.modelName(), 64),
                    mcp ? DiscoveryAssetType.MCP_CONFIGURATION : DiscoveryAssetType.AGENT,
                    sourceType,
                    truncate(sourceLocation, 1024),
                    request.accountName(), process, truncate(flow.executablePath(), 1024),
                    null, null, mcp ? 1 : 0,
                    flow.provider() != null || flow.modelName() != null ? 90 : 76,
                    mcp ? RiskLevel.MEDIUM : RiskLevel.LOW,
                    request.sensorType().name() + "_NETWORK_FLOW", evidence
            ));
        }

        if (request.observations().size() > limit) {
            warnings.add("观测数量超过服务端限制，已截断为 " + limit + " 条");
        }
        if (!observations.isEmpty()) {
            discoveryService.ingestEndpoint(new AgentDiscoveryDtos.EndpointObservationRequest(
                    request.endpointKey(), observations));
        }
        int mcp = (int) observations.stream()
                .filter(item -> item.assetType() == DiscoveryAssetType.MCP_CONFIGURATION).count();
        return new AgentDiscoveryDtos.HostObservationResponse(
                request.endpointKey(), request.sensorType(), inspected,
                observations.size() - mcp, mcp, observations.size(), List.copyOf(warnings)
        );
    }

    private boolean containsAgent(String signal) {
        return signal.contains("boss-agent") || signal.contains("helloboss")
                || signal.contains("agent-worker") || signal.contains("ai-agent")
                || signal.contains("agent.exe") || signal.contains("agent.py")
                || signal.contains("langchain") || signal.contains("langgraph")
                || signal.contains("crewai") || signal.contains("autogen") || signal.contains("mastra")
                || signal.contains("semantic-kernel") || signal.contains("semantic_kernel")
                || signal.contains("openai-agents") || signal.contains("dify")
                || signal.contains("flowise") || signal.contains("langflow")
                || signal.contains("copilot") || signal.contains("claude") || signal.contains("codex")
                || signal.contains("gemini");
    }

    private boolean ignoredSignal(String signal) {
        return signal.contains("eap-discovery-agent") || signal.contains("eap-content-proxy")
                || signal.contains("tat_agent") || signal.contains("/qcloud/")
                || signal.contains("node_exporter") || signal.contains("telegraf")
                || signal.contains("otelcol") || signal.contains("opentelemetry-collector");
    }

    private String inferProvider(String remote) {
        String value = remote == null ? "Unknown" : remote.toLowerCase(Locale.ROOT);
        if (value.contains("openai")) return "OpenAI";
        if (value.contains("anthropic")) return "Anthropic";
        if (value.contains("deepseek")) return "DeepSeek";
        if (value.contains("azure")) return "Azure AI";
        if (value.contains("google") || value.contains("gemini")) return "Google";
        return "Network";
    }

    private String fingerprint(Object... values) {
        try {
            String joined = String.join("|", Arrays.stream(values)
                    .map(value -> value == null ? "" : String.valueOf(value)).toList());
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(joined.getBytes(StandardCharsets.UTF_8));
            return "host-" + java.util.HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
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

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) return value.trim();
        }
        return "";
    }

    public static final class HostObservationDtos {
        private HostObservationDtos() { }

        public record HostObservationRequest(
                @NotNull HostObservationSensorType sensorType,
                @NotBlank @Size(max = 128) String endpointKey,
                @NotBlank @Size(max = 256) String hostName,
                @Size(max = 256) String accountName,
                @Size(max = 128) String operatingSystem,
                @Size(max = 64) String collectorVersion,
                @NotEmpty @Size(max = 1000) List<@Valid HostNetworkObservation> observations
        ) { }

        public record HostNetworkObservation(
                @Size(max = 256) String processName,
                @Min(0) Long processId,
                @Size(max = 1024) String executablePath,
                @Size(max = 256) String containerId,
                @Size(max = 256) String cgroupId,
                @Size(max = 256) String workloadUid,
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
