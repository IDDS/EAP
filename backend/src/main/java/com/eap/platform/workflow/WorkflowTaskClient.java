package com.eap.platform.workflow;

import com.eap.platform.registry.AgentRuntimeInstance;
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
    private final ScheduledExecutorService deadlines = Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().daemon().name("workflow-http-deadline").factory());
    public WorkflowTaskClient(WorkflowExecutionProperties properties, ObjectMapper mapper) {
        this.properties = properties; this.mapper = mapper;
    }
    public record Receipt(String status, String reason, Integer httpStatus) {}

    public Receipt invoke(AgentRuntimeInstance runtime, Map<String, Object> payload, Instant executionDeadline) {
        if (!properties.ready()) return new Receipt("FAILED", "工作流执行未启用或未配置认证与目标白名单", null);
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
            connection.setRequestProperty("Authorization", "Bearer " + properties.getToken());
            connection.setRequestProperty("Idempotency-Key", String.valueOf(payload.get("taskKey")));
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
            var result = mapper.readTree(response);
            if (result == null || !String.valueOf(payload.get("taskKey")).equals(result.path("taskKey").asText())) {
                return new Receipt("EXECUTION_UNKNOWN", "Runtime 任务标识不匹配，不能确认结果", httpStatus);
            }
            String status = result.path("status").asText();
            if (!status.equals("SUCCEEDED") && !status.equals("FAILED")) {
                return new Receipt("EXECUTION_UNKNOWN", "Runtime 未提供明确任务终态", httpStatus);
            }
            return new Receipt(status, status.equals("SUCCEEDED") ? "Runtime 已确认任务完成" : "Runtime 已确认任务失败", httpStatus);
        } catch (Exception exception) {
            return new Receipt("EXECUTION_UNKNOWN", "Runtime 请求中断、超时或响应无效；请按任务标识核对，未自动重试", null);
        } finally { if (deadline != null) deadline.cancel(false); if (connection != null) connection.disconnect(); }
    }
    @PreDestroy public void close() { deadlines.shutdownNow(); }
}
