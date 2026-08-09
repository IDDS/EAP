package com.eap.platform.discovery;

import com.eap.platform.agent.RiskLevel;
import com.eap.platform.common.BusinessException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.LinkedHashMap;

@Service
public class ProtocolDiscoveryService {
    private static final String A2A_CARD_PATH = "/.well-known/agent-card.json";
    private static final String LEGACY_A2A_CARD_PATH = "/.well-known/agent.json";
    private static final List<String> MCP_SERVER_JSON_PATHS = List.of(
            "/server.json", "/.well-known/mcp/server.json", "/.well-known/mcp.json"
    );

    private final ProtocolDiscoveryProperties properties;
    private final AgentDiscoveryService discoveryService;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public ProtocolDiscoveryService(
            ProtocolDiscoveryProperties properties,
            AgentDiscoveryService discoveryService,
            ObjectMapper objectMapper
    ) {
        this.properties = properties;
        this.discoveryService = discoveryService;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(Math.max(500, properties.getConnectTimeoutMs())))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    @Transactional
    public AgentDiscoveryDtos.ProtocolProbeResponse probe(AgentDiscoveryDtos.ProtocolProbeRequest request) {
        URI target = validateTarget(request.targetUrl());
        List<String> warnings = new ArrayList<>();
        int endpointsRegistered = registerEndpoint(target);
        List<JsonDocument> documents = fetchDocuments(target, request.discoveryType(), warnings);
        int a2aCount = 0;
        int mcpCount = 0;
        for (JsonDocument document : documents) {
            try {
                if (isA2aCard(document.payload())) a2aCount += ingestA2a(target, document);
                else mcpCount += ingestMcp(target, document);
            } catch (RuntimeException exception) {
                warnings.add(document.url() + ": " + truncate(exception.getMessage(), 256));
            }
        }
        return new AgentDiscoveryDtos.ProtocolProbeResponse(
                request.targetUrl(), request.discoveryType(), endpointsRegistered, documents.size(),
                a2aCount, mcpCount, List.copyOf(warnings)
        );
    }

    public List<AgentDiscoveryDtos.ProtocolProbeResponse> probeConfiguredTargets() {
        if (!properties.isEnabled() || properties.getTargets() == null) return List.of();
        return properties.getTargets().stream()
                .filter(value -> value != null && !value.isBlank())
                .map(value -> {
                    try {
                        return probe(new AgentDiscoveryDtos.ProtocolProbeRequest(value.trim(), ProtocolDiscoveryType.AUTO));
                    } catch (RuntimeException exception) {
                        return new AgentDiscoveryDtos.ProtocolProbeResponse(
                                value.trim(), ProtocolDiscoveryType.AUTO, 0, 0, 0, 0,
                                List.of(truncate(exception.getMessage(), 256))
                        );
                    }
                }).toList();
    }

    private int registerEndpoint(URI target) {
        String endpointKey = "protocol-" + Integer.toUnsignedString(target.toString().hashCode(), 36);
        discoveryService.registerEndpoint(new AgentDiscoveryDtos.EndpointRegistrationRequest(
                endpointKey, target.getHost(), null, "REMOTE", "protocol-discovery/0.1"
        ));
        return 1;
    }

    private List<JsonDocument> fetchDocuments(URI target, ProtocolDiscoveryType type, List<String> warnings) {
        List<URI> candidates = new ArrayList<>();
        switch (type) {
            case A2A -> candidates.add(resolvePath(target, A2A_CARD_PATH));
            case MCP_SERVER_JSON -> candidates.addAll(mcpCandidates(target));
            case MCP_REGISTRY -> candidates.add(resolvePath(target, "/v0.1/servers?limit=" + properties.getMaxServersPerRegistry()));
            case AUTO -> {
                candidates.add(resolvePath(target, A2A_CARD_PATH));
                candidates.add(resolvePath(target, LEGACY_A2A_CARD_PATH));
                candidates.addAll(mcpCandidates(target));
                if (target.getPath() == null || !target.getPath().contains("/v0.1/servers")) {
                    candidates.add(resolvePath(target, "/v0.1/servers?limit=" + properties.getMaxServersPerRegistry()));
                }
            }
        }
        List<JsonDocument> documents = new ArrayList<>();
        for (URI candidate : candidates.stream().distinct().toList()) {
            try {
                JsonNode payload = fetchJson(candidate);
                if (payload != null) documents.add(new JsonDocument(candidate.toString(), payload));
            } catch (RuntimeException exception) {
                warnings.add(candidate + ": " + truncate(exception.getMessage(), 256));
            }
        }
        return documents;
    }

    private int ingestA2a(URI target, JsonDocument document) {
        JsonNode card = document.payload();
        String endpointKey = "protocol-" + Integer.toUnsignedString(target.toString().hashCode(), 36);
        List<Map<String, Object>> skills = new ArrayList<>();
        if (card.path("skills").isArray()) {
            card.path("skills").elements().forEachRemaining(skill -> {
                if (skills.size() >= 32) return;
                Map<String, Object> summary = new LinkedHashMap<>();
                putIfPresent(summary, "id", truncate(text(skill, "id"), 128));
                putIfPresent(summary, "name", truncate(text(skill, "name", "title"), 256));
                putIfPresent(summary, "tags", truncate(text(skill, "tags"), 256));
                skills.add(summary);
            });
        }
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("protocol", "A2A");
        evidence.put("cardUrl", document.url());
        putIfPresent(evidence, "agentUrl", truncate(text(card, "url"), 1024));
        putIfPresent(evidence, "description", truncate(text(card, "description"), 512));
        evidence.put("skills", skills);
        evidence.put("streaming", card.path("capabilities").path("streaming").asBoolean(false));
        evidence.put("pushNotifications", card.path("capabilities").path("pushNotifications").asBoolean(false));
        evidence.put("authenticationDeclared", card.has("authentication"));
        AgentDiscoveryDtos.EndpointEvidenceRequest observation = new AgentDiscoveryDtos.EndpointEvidenceRequest(
                "a2a-" + Integer.toUnsignedString(document.url().hashCode(), 36),
                truncate(text(card, "name", "title"), 256, "Unnamed A2A Agent"),
                "A2A", "A2A Agent", truncate(text(card, "version"), 64),
                DiscoveryAssetType.AGENT, DiscoverySourceType.A2A, document.url(),
                null, null, null, null, false, 0, 92, RiskLevel.LOW,
                "A2A_AGENT_CARD", evidence
        );
        discoveryService.ingestEndpoint(new AgentDiscoveryDtos.EndpointObservationRequest(endpointKey, List.of(observation)));
        return 1;
    }

    private int ingestMcp(URI target, JsonDocument document) {
        JsonNode root = document.payload();
        List<JsonNode> servers = new ArrayList<>();
        if (root.path("servers").isArray()) {
            root.path("servers").forEach(item -> servers.add(item.path("server").isObject() ? item.path("server") : item));
        } else if (root.path("server").isObject()) {
            servers.add(root.path("server"));
        } else if (root.has("name") && root.has("version")) {
            servers.add(root);
        }
        if (servers.isEmpty()) return 0;

        String endpointKey = "protocol-" + Integer.toUnsignedString(target.toString().hashCode(), 36);
        List<AgentDiscoveryDtos.EndpointEvidenceRequest> observations = new ArrayList<>();
        for (JsonNode server : servers.stream().limit(properties.getMaxServersPerRegistry()).toList()) {
            String name = truncate(text(server, "name", "title"), 256, "Unnamed MCP Server");
            int packages = server.path("packages").isArray() ? server.path("packages").size() : 0;
            int remotes = server.path("remotes").isArray() ? server.path("remotes").size() : 0;
            Map<String, Object> evidence = new LinkedHashMap<>();
            evidence.put("protocol", "MCP");
            evidence.put("registryUrl", document.url());
            putIfPresent(evidence, "description", truncate(text(server, "description"), 512));
            putIfPresent(evidence, "repository", truncate(server.path("repository").path("url").asText(null), 1024));
            evidence.put("packageCount", packages);
            evidence.put("remoteCount", remotes);
            evidence.put("transportTypes", transportTypes(server));
            observations.add(new AgentDiscoveryDtos.EndpointEvidenceRequest(
                    "mcp-" + Integer.toUnsignedString((document.url() + ":" + name).hashCode(), 36),
                    name, "MCP", "MCP Server", truncate(text(server, "version"), 64),
                    DiscoveryAssetType.MCP_CONFIGURATION, DiscoverySourceType.MCP_REGISTRY,
                    document.url(), null, null, null, null, false, Math.max(1, remotes), 88,
                    packages > 0 ? RiskLevel.MEDIUM : RiskLevel.LOW,
                    "MCP_SERVER_METADATA", evidence
            ));
        }
        discoveryService.ingestEndpoint(new AgentDiscoveryDtos.EndpointObservationRequest(endpointKey, observations));
        return observations.size();
    }

    private List<String> transportTypes(JsonNode server) {
        List<String> types = new ArrayList<>();
        if (server.path("packages").isArray()) server.path("packages").forEach(item -> types.add(text(item, "transport")));
        if (server.path("remotes").isArray()) server.path("remotes").forEach(item -> types.add(text(item, "type", "transport")));
        return types.stream().filter(value -> value != null && !value.isBlank()).distinct().limit(16).toList();
    }

    private boolean isA2aCard(JsonNode payload) {
        return (payload.has("skills") && (payload.has("capabilities") || payload.has("url")))
                || (payload.has("protocolVersion") && payload.has("skills"));
    }

    private JsonNode fetchJson(URI uri) {
        try {
            HttpRequest request = HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofMillis(Math.max(500, properties.getReadTimeoutMs())))
                    .header("Accept", "application/json")
                    .header("User-Agent", "EAP-Protocol-Discovery/0.1")
                    .GET().build();
            HttpResponse<InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream input = response.body()) {
                if (response.statusCode() < 200 || response.statusCode() >= 300) throw new IllegalStateException("HTTP " + response.statusCode());
                byte[] bytes = input.readNBytes(Math.max(1024, properties.getMaxResponseBytes()) + 1);
                if (bytes.length > properties.getMaxResponseBytes()) throw new IllegalStateException("响应超过大小限制");
                return objectMapper.readTree(bytes);
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("请求被中断");
        } catch (IOException exception) {
            throw new IllegalStateException("请求失败：" + exception.getClass().getSimpleName());
        }
    }

    private URI validateTarget(String raw) {
        final URI uri;
        try {
            uri = new URI(raw.trim());
        } catch (URISyntaxException exception) {
            throw new BusinessException("协议发现地址不合法");
        }
        if (!List.of("http", "https").contains(uri.getScheme().toLowerCase(Locale.ROOT))
                || uri.getHost() == null || uri.getUserInfo() != null) {
            throw new BusinessException("协议发现仅允许不带凭据的 HTTP/HTTPS 地址");
        }
        String host = uri.getHost().toLowerCase(Locale.ROOT);
        if (Set.of("169.254.169.254", "metadata.google.internal", "100.100.100.200").contains(host)) {
            throw new BusinessException("协议发现已拒绝云元数据地址");
        }
        if (!properties.isAllowPrivateNetworks()) {
            try {
                for (InetAddress address : InetAddress.getAllByName(host)) {
                    if (address.isAnyLocalAddress() || address.isLoopbackAddress()
                            || address.isLinkLocalAddress() || address.isSiteLocalAddress()) {
                        throw new BusinessException("协议发现默认拒绝私有网络地址，请显式开启 EAP_DISCOVERY_PROTOCOL_ALLOW_PRIVATE_NETWORKS");
                    }
                }
            } catch (IOException exception) {
                throw new BusinessException("无法解析协议发现主机");
            }
        }
        return uri;
    }

    private URI resolvePath(URI target, String path) {
        String base = target.toString();
        return URI.create(base.replaceAll("/$", "") + (path.startsWith("/") ? path : "/" + path));
    }

    private List<URI> mcpCandidates(URI target) {
        if (target.getPath() != null && target.getPath().endsWith(".json")) return List.of(target);
        return MCP_SERVER_JSON_PATHS.stream().map(path -> resolvePath(target, path)).toList();
    }

    private String text(JsonNode node, String... fields) {
        for (String field : fields) {
            JsonNode value = node.path(field);
            if (!value.isMissingNode() && !value.isNull()) {
                String result = value.isValueNode() ? value.asText() : value.toString();
                if (!result.isBlank()) return result;
            }
        }
        return null;
    }

    private void putIfPresent(Map<String, Object> target, String key, Object value) {
        if (value != null && !String.valueOf(value).isBlank()) target.put(key, value);
    }

    private String truncate(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }

    private String truncate(String value, int max, String fallback) {
        String result = truncate(value, max);
        return result == null || result.isBlank() ? fallback : result;
    }

    private record JsonDocument(String url, JsonNode payload) {}
}
