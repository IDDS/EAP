package com.eap.platform.gateway;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

@Component
public class OpenAiUpstreamClient {
    private final GatewayProperties properties;
    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    public OpenAiUpstreamClient(GatewayProperties properties, RestClient.Builder builder, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofMillis(properties.getConnectTimeoutMs()));
        requestFactory.setReadTimeout(Duration.ofMillis(properties.getReadTimeoutMs()));
        this.restClient = builder.baseUrl(properties.getUpstreamBaseUrl()).requestFactory(requestFactory).build();
    }

    public ResponseEntity<JsonNode> chatCompletions(JsonNode request) {
        var spec = restClient.post().uri("/v1/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON);
        authorize(spec);
        return spec.body(request).exchange((sent, response) -> jsonResponse(response));
    }

    public ResponseEntity<String> chatCompletionsStream(JsonNode request) {
        var spec = restClient.post().uri("/v1/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.TEXT_EVENT_STREAM, MediaType.APPLICATION_JSON);
        authorize(spec);
        return spec.body(request).exchange((sent, response) -> {
            byte[] body = readBounded(response, "stream_response_too_large");
            requireSuccess(response, body);
            MediaType contentType = response.getHeaders().getContentType();
            var charset = contentType != null && contentType.getCharset() != null
                    ? contentType.getCharset() : StandardCharsets.UTF_8;
            return ResponseEntity.status(response.getStatusCode()).headers(response.getHeaders())
                    .body(new String(body, charset));
        });
    }

    public ResponseEntity<JsonNode> models() {
        var spec = restClient.get().uri("/v1/models").accept(MediaType.APPLICATION_JSON);
        authorize(spec);
        return spec.exchange((sent, response) -> jsonResponse(response));
    }

    private ResponseEntity<JsonNode> jsonResponse(ClientHttpResponse response) throws IOException {
        byte[] body = readBounded(response, "upstream_response_too_large");
        requireSuccess(response, body);
        return ResponseEntity.status(response.getStatusCode()).headers(response.getHeaders())
                .body(body.length == 0 ? null : objectMapper.readTree(body));
    }

    private byte[] readBounded(ClientHttpResponse response, String overflowCode) throws IOException {
        long limit = Math.max(1, properties.getMaxResponseBytes());
        long expected = response.getHeaders().getContentLength();
        // Close the body before RestClient closes the response, including overflow/error paths.
        // This prevents response cleanup from draining a still-open, oversized stream.
        try (var input = response.getBody(); var output = new ByteArrayOutputStream()) {
            if (expected > limit) throw oversized(overflowCode);
            byte[] buffer = new byte[(int) Math.min(8192, limit)];
            long total = 0;
            int count;
            while ((count = input.read(buffer, 0, (int) Math.min(buffer.length, limit - total + 1))) != -1) {
                total += count;
                if (total > limit) throw oversized(overflowCode);
                output.write(buffer, 0, count);
            }
            if (expected >= 0 && total != expected) {
                throw new GatewayException(HttpStatus.BAD_GATEWAY, "upstream_response_incomplete", "上游模型响应未完整接收");
            }
            return output.toByteArray();
        }
    }

    private GatewayException oversized(String code) {
        return new GatewayException(HttpStatus.BAD_GATEWAY, code, "上游模型响应超过网关允许的最大大小");
    }

    private void requireSuccess(ClientHttpResponse response, byte[] body) throws IOException {
        if (response.getStatusCode().isError()) {
            throw new RestClientResponseException("上游模型返回错误", response.getStatusCode().value(),
                    response.getStatusText(), response.getHeaders(), body, StandardCharsets.UTF_8);
        }
    }

    private void authorize(RestClient.RequestHeadersSpec<?> spec) {
        if (!properties.getUpstreamApiKey().isBlank()) {
            spec.header(HttpHeaders.AUTHORIZATION, "Bearer " + properties.getUpstreamApiKey());
        }
    }
}
