package com.eap.platform.management;

import com.eap.platform.common.BusinessException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.net.InetAddress;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

@Component
public class ManagementConnectorClient {
    private final ManagementConnectorProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public ManagementConnectorClient(ManagementConnectorProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(Math.max(500, properties.getConnectTimeoutMs())))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    public JsonNode fetch(ManagementValueConnector connector) {
        URI uri = targetUri(connector);
        HttpRequest.Builder request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofMillis(Math.max(1000, properties.getReadTimeoutMs())))
                .header(HttpHeaders.ACCEPT, "application/json")
                .header(HttpHeaders.USER_AGENT, "EAP-Business-Value-Connector/0.1")
                .GET();
        authorize(request, connector);
        try {
            HttpResponse<InputStream> response = httpClient.send(request.build(), HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream input = response.body()) {
                if (response.statusCode() < 200 || response.statusCode() >= 300) {
                    throw new BusinessException("业务系统返回 HTTP " + response.statusCode());
                }
                int maxBytes = Math.max(1024, properties.getMaxResponseBytes());
                byte[] payload = input.readNBytes(maxBytes + 1);
                if (payload.length > maxBytes) throw new BusinessException("业务系统响应超过大小限制");
                return objectMapper.readTree(payload);
            }
        } catch (BusinessException exception) {
            throw exception;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new BusinessException("业务系统同步被中断");
        } catch (Exception exception) {
            throw new BusinessException("无法读取业务系统：" + safeMessage(exception));
        }
    }

    private URI targetUri(ManagementValueConnector connector) {
        try {
            URI base = URI.create(connector.endpointUrl);
            if (!("http".equalsIgnoreCase(base.getScheme()) || "https".equalsIgnoreCase(base.getScheme()))
                    || base.getHost() == null || base.getUserInfo() != null || base.getFragment() != null) {
                throw new BusinessException("连接器地址必须是无内嵌凭据的 HTTP/HTTPS URL");
            }
            validateHost(base.getHost());
            if (connector.incrementalParameter == null || connector.incrementalParameter.isBlank()
                    || connector.lastSuccessAt == null) return base;
            String separator = base.getRawQuery() == null ? "?" : "&";
            String query = URLEncoder.encode(connector.incrementalParameter.trim(), StandardCharsets.UTF_8)
                    + "=" + URLEncoder.encode(connector.lastSuccessAt.toString(), StandardCharsets.UTF_8);
            return URI.create(base + separator + query);
        } catch (BusinessException exception) {
            throw exception;
        } catch (IllegalArgumentException exception) {
            throw new BusinessException("连接器地址格式不正确");
        }
    }

    private void validateHost(String host) {
        if ("169.254.169.254".equals(host) || "metadata.google.internal".equalsIgnoreCase(host)) {
            throw new BusinessException("不允许连接云实例元数据地址");
        }
        if (properties.isAllowPrivateNetworks()) return;
        try {
            for (InetAddress address : InetAddress.getAllByName(host)) {
                if (address.isAnyLocalAddress() || address.isLoopbackAddress()
                        || address.isLinkLocalAddress() || address.isSiteLocalAddress() || sharedAddress(address)) {
                    throw new BusinessException("当前策略不允许连接内网地址；如需访问企业内网系统，请显式启用私网连接器");
                }
            }
        } catch (BusinessException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new BusinessException("无法解析业务系统主机");
        }
    }

    private boolean sharedAddress(InetAddress address) {
        byte[] bytes = address.getAddress();
        return bytes.length == 4 && (bytes[0] & 0xff) == 100 && ((bytes[1] & 0xc0) == 64);
    }

    private void authorize(HttpRequest.Builder request, ManagementValueConnector connector) {
        String authType = connector.authType == null ? "NONE" : connector.authType.toUpperCase();
        if ("NONE".equals(authType)) return;
        if (!"BEARER_ENV".equals(authType) && !"HEADER_ENV".equals(authType)) {
            throw new BusinessException("连接器认证方式仅支持 NONE、BEARER_ENV 或 HEADER_ENV");
        }
        if (connector.credentialEnvKey == null || connector.credentialEnvKey.isBlank()) {
            throw new BusinessException("连接器未配置凭据环境变量名");
        }
        String secret = System.getenv(connector.credentialEnvKey.trim());
        if (secret == null || secret.isBlank()) {
            throw new BusinessException("运行环境中不存在连接器凭据 " + connector.credentialEnvKey.trim());
        }
        String header = "BEARER_ENV".equals(authType) ? HttpHeaders.AUTHORIZATION
                : firstNonBlank(connector.authHeader, "X-API-Key");
        String prefix = "BEARER_ENV".equals(authType) ? firstNonBlank(connector.authPrefix, "Bearer ")
                : firstNonBlank(connector.authPrefix, "");
        request.header(header, prefix + secret);
    }

    private String firstNonBlank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private String safeMessage(Exception exception) {
        String message = exception.getMessage();
        if (message == null || message.isBlank()) return exception.getClass().getSimpleName();
        return message.replaceAll("[\\r\\n\\t]", " ").substring(0, Math.min(message.length(), 256));
    }
}
