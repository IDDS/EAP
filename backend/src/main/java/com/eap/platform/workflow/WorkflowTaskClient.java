package com.eap.platform.workflow;

import com.eap.platform.registry.AgentRuntimeInstance;
import com.eap.platform.substrate.SubstrateActorRef;
import com.eap.platform.substrate.SubstrateClient;
import com.eap.platform.substrate.SubstrateProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import java.net.*;
import java.time.Instant;
import java.time.Duration;
import jakarta.annotation.PreDestroy;
import java.util.concurrent.*;
import java.util.Locale;
import java.util.Map;

/** EAP-owned synchronous task protocol; no control ACK is treated as a completed task. */
@Component
public class WorkflowTaskClient {
    private final WorkflowExecutionProperties properties;
    private final ObjectMapper mapper;
    private final SubstrateProperties substrate;
    private final SubstrateClient substrateClient;
    private final com.eap.platform.registry.RuntimeConnectionService connections;
    private final com.eap.platform.registry.ScopedToolService scopedTools;
    @org.springframework.beans.factory.annotation.Autowired
    private com.eap.platform.observability.ObservationCoverageService coverage;
    @org.springframework.beans.factory.annotation.Autowired
    private org.springframework.jdbc.core.JdbcTemplate db;
    @org.springframework.beans.factory.annotation.Autowired
    private com.eap.platform.observability.RuntimePlacementEvidenceService placementEvidence;
    private final ScheduledExecutorService deadlines = Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().daemon().name("workflow-http-deadline").factory());
    public WorkflowTaskClient(WorkflowExecutionProperties properties, ObjectMapper mapper,
                              SubstrateProperties substrate, SubstrateClient substrateClient, com.eap.platform.registry.RuntimeConnectionService connections, com.eap.platform.registry.ScopedToolService scopedTools) {
        this.properties = properties; this.mapper = mapper; this.substrate = substrate; this.substrateClient = substrateClient; this.connections=connections; this.scopedTools=scopedTools;
    }
    public record Receipt(String status, String reason, Integer httpStatus, com.fasterxml.jackson.databind.JsonNode output, String runtimeTraceId) {
        public Receipt(String status, String reason, Integer httpStatus, com.fasterxml.jackson.databind.JsonNode output) { this(status, reason, httpStatus, output, null); }
        public Receipt(String status, String reason, Integer httpStatus) { this(status, reason, httpStatus, null); }
    }

    @FunctionalInterface
    public interface Prepared { void beforeDispatch(); }
    public Receipt invoke(AgentRuntimeInstance runtime, Map<String, Object> payload, Instant executionDeadline) {
        return invoke(runtime, payload, executionDeadline, () -> {});
    }
    public Receipt invoke(AgentRuntimeInstance runtime, Map<String, Object> payload, Instant executionDeadline, Prepared prepared) {
        if (!properties.ready()) return new Receipt("FAILED", "工作流执行未启用或未配置认证与目标白名单", null);
        if (runtime.hasBoundIdentity() && !connections.executable(runtime)) throw new IllegalArgumentException("外部实例尚未验证任务协议或凭证已失效");
        if (isTool(payload) && (!runtime.hasBoundIdentity() || !SubstrateActorRef.isSubstrate(runtime.getProtocol())))
            return new Receipt("FAILED", "工具任务需要已验证的实例专属接入，未派发任务", null);
        if (SubstrateActorRef.isSubstrate(runtime.getProtocol())) return invokeSubstrate(runtime, payload, executionDeadline, prepared);
        URI uri;
        try {
            uri = URI.create(runtime.getEndpoint());
            if (uri.getHost() == null || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null
                    || !("https".equalsIgnoreCase(uri.getScheme())
                    || properties.isAllowInsecureHttp() && "http".equalsIgnoreCase(uri.getScheme()))
                    || !properties.hosts().contains(uri.getHost().toLowerCase(Locale.ROOT))) {
                return new Receipt("FAILED", "Runtime 地址未满足协议或目标白名单要求", null);
            }
            for (InetAddress address : InetAddress.getAllByName(uri.getHost())) {
                if (!properties.isAllowPrivateNetworks() && (address.isAnyLocalAddress() || address.isLoopbackAddress()
                        || address.isLinkLocalAddress() || address.isSiteLocalAddress()
                        || address.getAddress().length == 16 && (address.getAddress()[0] & 0xfe) == 0xfc)) {
                    return new Receipt("FAILED", "Runtime 地址命中私网限制", null);
                }
            }
        } catch (Exception exception) { return new Receipt("FAILED", "Runtime 地址无效或无法解析", null); }
        prepared.beforeDispatch();
        return post(runtime, uri, null, properties.getToken(), payload, executionDeadline);
    }

    /**
     * Substrate actors are reached only through the operator-configured router; the registered endpoint
     * names the actor and never becomes a URL, so the target allowlist does not apply to it. The actor is
     * woken first so a failed wakeup is reported as FAILED before any task side effect can occur.
     */
    private Receipt invokeSubstrate(AgentRuntimeInstance runtime, Map<String, Object> payload, Instant executionDeadline, Prepared prepared) {
        if (!substrate.isEnabled()) return new Receipt("FAILED", "Substrate Runtime 集成未启用", null);
        SubstrateActorRef actor;
        URI uri;
        try {
            actor = SubstrateActorRef.parse(runtime.getEndpoint());
            uri = URI.create(substrate.getRouterUrl().replaceAll("/+$", "") + substrate.getTaskPath());
            if (uri.getHost() == null || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null
                    || !("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))) {
                return new Receipt("FAILED", "Substrate 路由器地址配置无效", null);
            }
        } catch (IllegalArgumentException exception) { return new Receipt("FAILED", "Substrate Runtime 地址无效", null); }
        if(runtime.hasBoundIdentity())connections.checkIdentity(runtime);
        String dispatchToken = properties.getToken();
        boolean isolationRequired = false;
        if (!isTool(payload) && db != null) {
            String template = runtime.getBoundActorTemplate();
            if (template == null) {
                var inventory = substrateClient.inspect(actor);
                if (inventory != null) template = inventory.template();
            }
            isolationRequired = db.queryForObject("select count(*) from substrate_agent_deployments where source_type='RUNNER' and atespace=? and (template_name=? or instance_key=?)",
                    Long.class, actor.atespace(), template, runtime.getInstanceKey()) > 0;
        }
        try {
            substrateClient.resume(actor);
        } catch (SubstrateClient.SubstrateException exception) {
            return new Receipt("FAILED", "唤醒 Actor 失败（" + exception.code() + "），未派发任务", null);
        }
        if (runtime.hasBoundIdentity()) dispatchToken = connections.dispatchToken(runtime);
        if ((isTool(payload) || isolationRequired) && !runtimeReady(actor, dispatchToken, executionDeadline, isTool(payload))) {
            String reason = isTool(payload) ? "实例未确认启用受控工具，未派发任务；请检查运行程序与工具配置"
                    : "此平台 Runner 尚未支持任务隔离，请发布新版并使用新实例；未派发任务";
            // Preparation failure is not an exclusive-idle proof. Reclamation belongs to the workflow owner.
            return new Receipt("FAILED", reason, null);
        }
        prepared.beforeDispatch();
        return post(runtime, uri, actor.routingValue(), dispatchToken, payload, executionDeadline);
    }

    private boolean isTool(Map<String, Object> payload) {
        return mapper.valueToTree(payload).path("context").has("eapTool");
    }

    /** Authenticated bounded capability read; old programs must never receive a tool as a model task. */
    private boolean runtimeReady(SubstrateActorRef actor, String token, Instant executionDeadline, boolean tool) {
        HttpURLConnection connection = null;
        ScheduledFuture<?> timer = null;
        try {
            long remaining = Math.min(12000, Duration.between(Instant.now(), executionDeadline).toMillis());
            if (remaining <= 0) return false;
            URI uri = URI.create(substrate.getRouterUrl().replaceAll("/+$", "") + "/eap/state");
            connection = (HttpURLConnection) uri.toURL().openConnection();
            HttpURLConnection active = connection;
            timer = deadlines.schedule(active::disconnect, remaining, TimeUnit.MILLISECONDS);
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout((int) Math.min(3000, remaining));
            connection.setReadTimeout((int) Math.min(9000, remaining));
            connection.setRequestProperty("Authorization", "Bearer " + token);
            connection.setRequestProperty(SubstrateActorRef.ROUTING_HEADER, actor.routingValue());
            if (connection.getResponseCode() != 200) return false;
            byte[] body;
            try (var in = connection.getInputStream()) { body = in.readNBytes(4097); }
            if (body.length > 4096) return false;
            var state = mapper.readTree(body);
            if (!tool) return "EXPLICIT_V1".equals(state.path("conversationMode").asText());
            var enabled = state.path("tools").path("enabled");
            return enabled.isBoolean() && enabled.booleanValue();
        } catch (Exception e) { return false; }
        finally { if (timer != null) timer.cancel(false); if (connection != null) connection.disconnect(); }
    }

    /** Only explicitly opted-in, dedicated workflow actors may be suspended after a known success. */
    public void suspend(AgentRuntimeInstance runtime) {
        if (!substrate.isEnabled() || !SubstrateActorRef.isSubstrate(runtime.getProtocol())) {
            throw new IllegalArgumentException("完成后休眠仅支持 Substrate Runtime");
        }
        if (runtime.hasBoundIdentity()) connections.checkIdentity(runtime);
        var state = substrateClient.suspend(SubstrateActorRef.parse(runtime.getEndpoint()));
        if (!"SUSPENDED".equals(state.state())) throw new IllegalStateException("Actor 未确认休眠");
    }

    private Receipt post(AgentRuntimeInstance runtime, URI uri, String targetActor, String dispatchToken, Map<String, Object> payload, Instant executionDeadline) {
        String key = String.valueOf(payload.get("taskKey"));
        String raw = null;
        if (coverage != null && !isTool(payload) && executionDeadline.isAfter(Instant.now())) {
            Instant httpDeadline = Instant.now().plusMillis((long)properties.getConnectTimeoutMs() + properties.getReadTimeoutMs());
            raw = coverage.runtimeDispatch(key, runtime, executionDeadline.isBefore(httpDeadline) ? executionDeadline : httpDeadline);
        }
        Map<String,Object> envelope = new java.util.LinkedHashMap<>(payload);
        if (raw != null) envelope.put("traceId", raw);
        if (raw != null && placementEvidence != null)
            placementEvidence.request(key, raw, runtime, com.eap.platform.observability.RuntimePlacementEvidenceStore.Phase.DISPATCH_REQUESTED);
        var receipt = send(runtime, uri, targetActor, dispatchToken, envelope, executionDeadline);
        if (raw != null) coverage.runtimeReceipt(key, receipt.status(), receipt.runtimeTraceId());
        if (raw != null && placementEvidence != null)
            placementEvidence.request(key, raw, runtime, com.eap.platform.observability.RuntimePlacementEvidenceStore.Phase.RECEIPT_RETURNED);
        return receipt;
    }

    private Receipt send(AgentRuntimeInstance runtime, URI uri, String targetActor, String dispatchToken, Map<String, Object> payload, Instant executionDeadline) {
        HttpURLConnection connection = null;
        ScheduledFuture<?> deadline = null;
        try {
            long remainingMs = Duration.between(Instant.now(), executionDeadline).toMillis();
            if (remainingMs <= 0) return new Receipt("EXECUTION_UNKNOWN", "执行期限已到，未派发任务", null);
            long requestBudgetMs = Math.min(remainingMs, (long) properties.getConnectTimeoutMs() + properties.getReadTimeoutMs());
            connection = (HttpURLConnection) uri.toURL().openConnection();
            HttpURLConnection active = connection;
            deadline = deadlines.schedule(active::disconnect,
                    requestBudgetMs, TimeUnit.MILLISECONDS);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestMethod("POST");
            connection.setConnectTimeout((int) Math.min(remainingMs, properties.getConnectTimeoutMs()));
            connection.setReadTimeout((int) Math.min(remainingMs, properties.getReadTimeoutMs()));
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("Authorization", "Bearer " + dispatchToken);
            connection.setRequestProperty("Idempotency-Key", String.valueOf(payload.get("taskKey")));
            if (targetActor != null) connection.setRequestProperty(SubstrateActorRef.ROUTING_HEADER, targetActor);
            byte[] body = mapper.writeValueAsBytes(payload);
            connection.setFixedLengthStreamingMode(body.length);
            try (var output = connection.getOutputStream()) { output.write(body); }
            int httpStatus = connection.getResponseCode();
            if (httpStatus != 200) return new Receipt("EXECUTION_UNKNOWN", "Runtime 未返回终态任务响应，请核对任务标识", httpStatus);
            byte[] response;
            try (var input = connection.getInputStream()) {
                response = input.readNBytes(properties.getMaxResponseBytes() + 1);
            }
            if (response.length > properties.getMaxResponseBytes()) return new Receipt("EXECUTION_UNKNOWN", "Runtime 响应超限，结果未知", httpStatus);
            var result = mapper.reader().with(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                    .with(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS,
                            com.fasterxml.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).readTree(response);
            if (result == null || !String.valueOf(payload.get("taskKey")).equals(result.path("taskKey").asText())) {
                return new Receipt("EXECUTION_UNKNOWN", "Runtime 任务标识不匹配，不能确认结果", httpStatus);
            }
            String status = result.path("status").asText();
            if (!status.equals("SUCCEEDED") && !status.equals("FAILED")) {
                return new Receipt("EXECUTION_UNKNOWN", "Runtime 未提供明确任务终态", httpStatus);
            }
            if (isTool(payload) && status.equals("SUCCEEDED") && (!result.path("tool").path("executed").isBoolean()
                    || !result.path("tool").path("executed").booleanValue()
                    || !result.path("tool").path("receiptConfirmed").isBoolean()
                    || !result.path("tool").path("receiptConfirmed").booleanValue()
                    || !confirmedTool(runtime, payload, result))) {
                return new Receipt("EXECUTION_UNKNOWN", "工具回执未确认，可能已执行；请按任务标识核对，未自动重试", httpStatus);
            }
            var output = status.equals("SUCCEEDED") ? result.get("output") : null;
            if (output != null && mapper.writeValueAsBytes(output).length > 16384) {
                return new Receipt("EXECUTION_UNKNOWN", "Runtime 输出超过 16KB，未截断或传递；请按任务标识核对", httpStatus);
            }
            return new Receipt(status, status.equals("SUCCEEDED") ? "Runtime 已确认任务完成" : failureReason(result), httpStatus, output, result.path("traceId").isTextual() ? result.path("traceId").asText() : null);
        } catch (Exception exception) {
            return new Receipt("EXECUTION_UNKNOWN", "Runtime 请求中断、超时或响应无效；请按任务标识核对，未自动重试", null);
        } finally { if (deadline != null) deadline.cancel(false); if (connection != null) connection.disconnect(); }
    }
    private String failureReason(com.fasterxml.jackson.databind.JsonNode result) {
        // Only fixed protocol codes may become persisted/user-visible reasons, never provider text.
        return switch (result.path("reasonCode").asText()) {
            case "MODEL_OUTPUT_TRUNCATED" -> "模型输出达到上限而被截断（MODEL_OUTPUT_TRUNCATED）；未传递给后续步骤";
            case "MODEL_OUTPUT_EMPTY" -> "模型未返回有效正文（MODEL_OUTPUT_EMPTY）；未传递给后续步骤";
            case "MODEL_OUTPUT_FILTERED" -> "模型响应被过滤（MODEL_OUTPUT_FILTERED）；未传递给后续步骤";
            case "MODEL_TOOL_CALL_UNSUPPORTED" -> "模型返回了不支持的工具调用（MODEL_TOOL_CALL_UNSUPPORTED）；未执行工具";
            case "MODEL_FINISH_UNCONFIRMED" -> "模型未确认正常结束（MODEL_FINISH_UNCONFIRMED）；未传递给后续步骤";
            default -> "Runtime 已确认任务失败";
        };
    }
    private boolean confirmedTool(AgentRuntimeInstance runtime, Map<String,Object> payload, com.fasterxml.jackson.databind.JsonNode result) {
        var tool=mapper.valueToTree(payload).path("context").path("eapTool");
        if (!com.eap.platform.registry.ScopedToolService.TOOL.equals(tool.path("name").asText()))
            return "SUCCEEDED".equals(result.path("tool").path("state").asText());
        if (!"TOOL_STORE_VERIFIED".equals(result.path("tool").path("state").asText())) return false;
        var args=tool.path("arguments");
        if (!args.path("delta").isIntegralNumber() || !args.path("delta").canConvertToLong()) return false;
        var request=new com.eap.platform.registry.ScopedToolDtos.Execute(args.path("grantId").asText(),args.path("operationId").asText(),args.path("resourceId").asText(),args.path("delta").longValue());
        return scopedTools.verifyEffect(runtime.getInstanceKey(),request,result.path("output"));
    }
    @PreDestroy public void close() { deadlines.shutdownNow(); }
}
