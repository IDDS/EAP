package com.eap.platform.control;

import com.eap.platform.common.BusinessException;
import com.eap.platform.registry.AgentRuntimeInstance;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.net.InetAddress;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

@Component
public class HttpAgentControlAdapter implements AgentControlAdapter {
    private final ControlDispatchProperties properties;
    private final ObjectMapper objectMapper;

    public HttpAgentControlAdapter(ControlDispatchProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Override
    public DispatchResult dispatch(AgentControlCommand command, AgentRuntimeInstance runtime) {
        if (runtime.getProtocol() == null || !"HTTP".equalsIgnoreCase(runtime.getProtocol())) {
            return new DispatchResult(ControlCommandStatus.FAILED, "HTTP_RUNTIME", "运行实例协议不是 HTTP", null, null);
        }
        URI endpoint = safeEndpoint(runtime.getEndpoint());
        URI target = endpoint.resolve(appendPath(endpoint.getPath(), properties.getPath()));
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofMillis(properties.getConnectTimeoutMs()));
        requestFactory.setReadTimeout(Duration.ofMillis(properties.getReadTimeoutMs()));
        RestClient client = RestClient.builder().requestFactory(requestFactory).build();
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("commandKey", command.commandKey);
        envelope.put("agentId", command.agentId);
        envelope.put("commandType", command.commandType);
        envelope.put("issuedBy", command.issuedBy);
        envelope.put("payload", parsePayload(command.payloadJson));
        try {
            byte[] requestBody = objectMapper.writeValueAsBytes(envelope);
            var response = client.post().uri(target)
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON, MediaType.TEXT_PLAIN, MediaType.ALL)
                    .header("X-EAP-Control-Command-Key", command.commandKey)
                    .header("X-EAP-Agent-Id", String.valueOf(command.agentId))
                    .header("Idempotency-Key", command.commandKey)
                    .body(requestBody)
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, (request, result) -> {
                        throw new DispatchHttpException(result.getStatusCode().value(), "runtime 返回 HTTP " + result.getStatusCode().value());
                    })
                    .toBodilessEntity();
            return new DispatchResult(ControlCommandStatus.ACKED, "HTTP_RUNTIME", response.getStatusCode().toString(),
                    response.getStatusCode().value(), Instant.now());
        } catch (DispatchHttpException exception) {
            return new DispatchResult(ControlCommandStatus.FAILED, "HTTP_RUNTIME", exception.getMessage(), exception.httpStatus, null);
        } catch (RuntimeException exception) {
            return new DispatchResult(ControlCommandStatus.FAILED, "HTTP_RUNTIME", normalize(exception.getMessage()), null, null);
        } catch (Exception exception) {
            return new DispatchResult(ControlCommandStatus.FAILED, "HTTP_RUNTIME", "控制命令序列化失败", null, null);
        }
    }

    private URI safeEndpoint(String endpoint) {
        try {
            URI uri = URI.create(endpoint);
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            if (!("http".equals(scheme) || "https".equals(scheme)) || uri.getHost() == null) {
                throw new BusinessException("控制适配器仅支持 HTTP/HTTPS runtime endpoint");
            }
            if (!properties.isAllowPrivateNetworks() && isPrivate(uri.getHost())) {
                throw new BusinessException("runtime endpoint 命中私网/本机地址，已被控制面 SSRF 防护阻断");
            }
            return uri;
        } catch (IllegalArgumentException exception) {
            throw new BusinessException("runtime endpoint 无效");
        }
    }

    private boolean isPrivate(String host) {
        String normalized = host.toLowerCase(Locale.ROOT);
        if (normalized.equals("localhost") || normalized.endsWith(".localhost") || normalized.endsWith(".local")) return true;
        try {
            InetAddress address = InetAddress.getByName(host);
            return address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                    || address.isSiteLocalAddress() || "169.254.169.254".equals(address.getHostAddress());
        } catch (Exception ignored) {
            return false;
        }
    }

    private String appendPath(String basePath, String configuredPath) {
        String base = basePath == null ? "" : basePath;
        String suffix = configuredPath.startsWith("/") ? configuredPath : "/" + configuredPath;
        if (base.endsWith("/") && suffix.startsWith("/")) return base.substring(0, base.length() - 1) + suffix;
        return base + suffix;
    }

    private Object parsePayload(String payload) {
        if (payload == null || payload.isBlank()) return Map.of();
        try {
            JsonNode node = objectMapper.readTree(payload);
            return node == null ? payload : node;
        } catch (Exception ignored) {
            return payload;
        }
    }

    private String normalize(String message) {
        if (message == null || message.isBlank()) return "runtime 控制请求失败";
        return message.replaceAll("[\\r\\n\\t]", " ").substring(0, Math.min(message.length(), 480));
    }

    private static final class DispatchHttpException extends RuntimeException {
        private final int httpStatus;

        private DispatchHttpException(int httpStatus, String message) {
            super(message);
            this.httpStatus = httpStatus;
        }
    }
}
