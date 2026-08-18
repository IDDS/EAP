package com.eap.platform.discovery;

import com.eap.platform.agent.RiskLevel;
import com.eap.platform.common.BusinessException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class KubernetesDiscoveryService {
    private final KubernetesDiscoveryProperties properties;
    private final AgentDiscoveryService discoveryService;
    private final ObjectMapper objectMapper;

    public KubernetesDiscoveryService(
            KubernetesDiscoveryProperties properties,
            AgentDiscoveryService discoveryService,
            ObjectMapper objectMapper
    ) {
        this.properties = properties;
        this.discoveryService = discoveryService;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public AgentDiscoveryDtos.KubernetesDiscoveryResponse ingestWatch(JsonNode payload) {
        return ingest(payload, false);
    }

    @Transactional
    public AgentDiscoveryDtos.KubernetesDiscoveryResponse ingestAdmission(JsonNode payload) {
        return ingest(payload, true);
    }

    private AgentDiscoveryDtos.KubernetesDiscoveryResponse ingest(JsonNode payload, boolean admission) {
        if (!properties.isEnabled()) throw new BusinessException("Kubernetes 发现连接器未启用");
        if (payload == null || payload.isNull() || payload.toString().length() > Math.max(16_384, properties.getMaxPayloadBytes())) {
            throw new BusinessException("Kubernetes 发现事件超过大小限制");
        }
        JsonNode object = admission ? payload.path("request").path("object") : payload.path("object");
        if (!object.isObject()) object = payload;
        JsonNode metadata = object.path("metadata");
        String cluster = first(payload, "clusterName", "cluster", "clusterId");
        if (cluster == null) cluster = first(metadata, "clusterName", "cluster");
        cluster = clean(cluster, "unknown-cluster", 128);
        String namespace = clean(text(metadata, "namespace"), "default", 128);
        String kind = clean(text(object, "kind"), "Workload", 64);
        String workloadName = clean(text(metadata, "name", "generateName"), "unnamed-workload", 256);
        String uid = clean(text(metadata, "uid"), namespace + "/" + kind + "/" + workloadName, 256);
        String podName = clean(first(payload, "podName", "pod"), workloadName, 256);
        String endpointKey = "k8s-" + fingerprint(cluster + "/" + uid);
        discoveryService.registerEndpoint(new AgentDiscoveryDtos.EndpointRegistrationRequest(
                endpointKey, podName, namespace, "KUBERNETES", "kubernetes-discovery/0.1"
        ));

        List<JsonNode> containers = containers(object);
        List<String> warnings = new ArrayList<>();
        List<AgentDiscoveryDtos.EndpointEvidenceRequest> observations = new ArrayList<>();
        int inspected = 0;
        for (JsonNode container : containers.stream().limit(Math.max(1, properties.getMaxContainers())).toList()) {
            inspected++;
            String image = text(container, "image");
            String name = clean(text(container, "name"), image == null ? workloadName : imageName(image), 256);
            String envNames = envNames(container.path("env"));
            String labelsText = safeLabelValue(metadata.path("labels"), "eap.ai/identity", "eap.ai/agent", "ai.eap/agent", "eap.ai/framework", "app.kubernetes.io/component");
            String annotationText = safeLabelValue(metadata.path("annotations"), "eap.ai/identity", "eap.ai/agent", "ai.eap/agent", "eap.ai/framework", "eap.ai/auto-approve");
            String signal = String.join(" ", name, image == null ? "" : image, envNames, labelsText, annotationText).toLowerCase(Locale.ROOT);
            AgentSignatureCatalog.AgentSignatureRule signature = productSignature(signal);
            boolean explicitAgent = hasText(labelsText) || hasText(annotationText);
            boolean mcp = signal.contains("mcp") || signal.contains("modelcontextprotocol");
            boolean agent = explicitAgent || signature != null || containsAgentFramework(signal);
            if (!agent && !mcp) continue;

            boolean privileged = object.path("spec").path("template").path("spec").path("securityContext").path("privileged").asBoolean(false)
                    || container.path("securityContext").path("privileged").asBoolean(false);
            boolean hostNetwork = object.path("spec").path("hostNetwork").asBoolean(false)
                    || object.path("spec").path("template").path("spec").path("hostNetwork").asBoolean(false);
            Map<String, Object> evidence = new LinkedHashMap<>();
            evidence.put("sourceSystem", admission ? "KUBERNETES_ADMISSION" : "KUBERNETES_WATCH");
            evidence.put("cluster", cluster);
            evidence.put("namespace", namespace);
            evidence.put("workloadKind", kind);
            evidence.put("workloadName", workloadName);
            evidence.put("workloadObjectUid", uid);
            evidence.put("workloadUid", uid + ":" + name);
            evidence.put("containerName", name);
            putIfPresent(evidence, "identityKey", labelValue(metadata, "eap.ai/identity", "ai.eap/identity"));
            putIfPresent(evidence, "image", truncate(image, 512));
            putIfPresent(evidence, "envNames", truncate(envNames, 1024));
            putIfPresent(evidence, "frameworkSignals", truncate(labelsText + " " + annotationText, 512));
            evidence.put("privileged", privileged);
            evidence.put("hostNetwork", hostNetwork);
            evidence.put("ports", ports(container.path("ports")));

            String sourceLocation = "k8s://" + cluster + "/" + namespace + "/" + kind + "/" + workloadName + "/" + name;
            observations.add(new AgentDiscoveryDtos.EndpointEvidenceRequest(
                    "k8s-" + fingerprint(sourceLocation),
                    name,
                    mcp ? "MCP" : signature == null ? framework(signal) : signature.vendor(),
                    mcp ? "MCP Server Workload" : signature == null ? "Kubernetes Agent Workload" : signature.product(),
                    imageTag(image),
                    mcp ? DiscoveryAssetType.MCP_CONFIGURATION : DiscoveryAssetType.AGENT,
                    admission ? DiscoverySourceType.KUBERNETES_ADMISSION : DiscoverySourceType.KUBERNETES_WATCH,
                    truncate(sourceLocation, 1024), namespace, name, null,
                    booleanLabel(metadata, "eap.ai/auto-approve", "ai.eap/auto-approve"),
                    booleanLabel(metadata, "eap.ai/trusted-process", "ai.eap/trusted-process"),
                    mcp ? 1 : 0,
                    explicitAgent ? 90 : 82,
                    privileged || hostNetwork ? RiskLevel.HIGH : mcp ? RiskLevel.MEDIUM : RiskLevel.LOW,
                    admission ? "KUBERNETES_ADMISSION_WORKLOAD" : "KUBERNETES_WATCH_WORKLOAD",
                    evidence
            ));
        }
        if (!observations.isEmpty()) discoveryService.ingestEndpoint(
                new AgentDiscoveryDtos.EndpointObservationRequest(endpointKey, observations));
        int mcp = (int) observations.stream().filter(item -> item.assetType() == DiscoveryAssetType.MCP_CONFIGURATION).count();
        return new AgentDiscoveryDtos.KubernetesDiscoveryResponse(
                endpointKey,
                admission ? "ADMISSION" : clean(first(payload, "type", "eventType"), "UNKNOWN", 64),
                inspected,
                observations.size() - mcp,
                mcp,
                observations.size(),
                List.copyOf(warnings)
        );
    }

    private List<JsonNode> containers(JsonNode object) {
        JsonNode podSpec = object.path("spec");
        if (podSpec.path("template").path("spec").isObject()) podSpec = podSpec.path("template").path("spec");
        List<JsonNode> result = new ArrayList<>();
        addArray(result, podSpec.path("initContainers"));
        addArray(result, podSpec.path("containers"));
        return result;
    }

    private void addArray(List<JsonNode> target, JsonNode value) {
        if (value.isArray()) value.forEach(target::add);
    }

    private boolean containsAgentFramework(String value) {
        return value.contains("langgraph") || value.contains("langchain") || value.contains("crewai")
                || value.contains("autogen") || value.contains("semantic-kernel") || value.contains("semantic_kernel")
                || value.contains("mastra") || value.contains("agent") || value.contains("copilot")
                || value.contains("claude") || value.contains("codex") || value.contains("gemini");
    }

    private AgentSignatureCatalog.AgentSignatureRule productSignature(String value) {
        String normalized = value == null ? "" : value.toLowerCase(Locale.ROOT);
        return AgentSignatureCatalog.RULES.stream()
                .filter(rule -> java.util.stream.Stream.concat(
                                rule.processMarkers().stream(),
                                java.util.stream.Stream.concat(rule.contentMarkers().stream(), rule.packageNames().stream()))
                        .filter(marker -> marker != null && !marker.isBlank())
                        .anyMatch(marker -> normalized.contains(marker.toLowerCase(Locale.ROOT))))
                .findFirst().orElse(null);
    }

    private String framework(String value) {
        if (value.contains("langgraph") || value.contains("langchain")) return "LangChain";
        if (value.contains("crewai")) return "CrewAI";
        if (value.contains("autogen")) return "AutoGen";
        if (value.contains("semantic-kernel") || value.contains("semantic_kernel")) return "Semantic Kernel";
        if (value.contains("mastra")) return "Mastra";
        return "Kubernetes";
    }

    private String imageName(String image) {
        String value = image == null ? "" : image;
        int slash = value.lastIndexOf('/');
        int colon = value.lastIndexOf(':');
        return value.substring(slash + 1, colon > slash ? colon : value.length());
    }

    private String imageTag(String image) {
        if (image == null || image.isBlank()) return null;
        int colon = image.lastIndexOf(':');
        int slash = image.lastIndexOf('/');
        return colon > slash ? truncate(image.substring(colon + 1), 64) : null;
    }

    private String envNames(JsonNode env) {
        if (!env.isArray()) return null;
        List<String> names = new ArrayList<>();
        env.forEach(item -> {
            String name = text(item, "name");
            if (name != null && !name.isBlank() && names.size() < 64) names.add(truncate(name, 128));
        });
        return names.isEmpty() ? null : String.join(",", names);
    }

    private List<String> ports(JsonNode ports) {
        if (!ports.isArray()) return List.of();
        List<String> result = new ArrayList<>();
        ports.forEach(item -> {
            if (result.size() >= 32) return;
            String name = text(item, "name");
            int port = item.path("containerPort").asInt(0);
            result.add((name == null ? "port" : truncate(name, 64)) + ":" + port);
        });
        return result;
    }

    private String safeLabelValue(JsonNode labels, String... keys) {
        if (!labels.isObject()) return null;
        List<String> values = new ArrayList<>();
        for (String key : keys) {
            String value = text(labels, key);
            if (value != null && !value.isBlank()) values.add(key + "=" + truncate(value, 256));
        }
        return values.isEmpty() ? null : String.join(" ", values);
    }

    private Boolean booleanLabel(JsonNode metadata, String... keys) {
        JsonNode labels = metadata.path("labels");
        JsonNode annotations = metadata.path("annotations");
        for (String key : keys) {
            String value = text(labels, key);
            if (value == null) value = text(annotations, key);
            if (value != null) return Boolean.parseBoolean(value);
        }
        return null;
    }

    private String labelValue(JsonNode metadata, String... keys) {
        String value = first(metadata.path("labels"), keys);
        return value == null ? first(metadata.path("annotations"), keys) : value;
    }

    private String first(JsonNode node, String... paths) {
        for (String path : paths) {
            String value = text(node, path);
            if (value != null && !value.isBlank()) return value;
        }
        return null;
    }

    private String text(JsonNode node, String... fields) {
        if (node == null || node.isMissingNode() || node.isNull()) return null;
        for (String field : fields) {
            JsonNode value = node.path(field);
            if (!value.isMissingNode() && !value.isNull()) {
                String result = value.isValueNode() ? value.asText() : value.toString();
                if (!result.isBlank()) return result;
            }
        }
        return null;
    }

    private String clean(String value, String fallback, int max) {
        return value == null || value.isBlank() ? fallback : truncate(value, max);
    }

    private boolean hasText(String value) { return value != null && !value.isBlank(); }

    private String truncate(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }

    private String fingerprint(String value) {
        return Integer.toUnsignedString(value.hashCode(), 36);
    }

    private void putIfPresent(Map<String, Object> target, String key, Object value) {
        if (value != null && !String.valueOf(value).isBlank()) target.put(key, value);
    }
}
