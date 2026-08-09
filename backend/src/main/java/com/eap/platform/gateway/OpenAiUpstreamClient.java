package com.eap.platform.gateway;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;

@Component
public class OpenAiUpstreamClient {
    private final GatewayProperties properties;
    private final RestClient restClient;

    public OpenAiUpstreamClient(GatewayProperties properties, RestClient.Builder builder) {
        this.properties = properties;
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
        return spec.body(request).retrieve().toEntity(JsonNode.class);
    }

    public ResponseEntity<String> chatCompletionsStream(JsonNode request) {
        var spec = restClient.post().uri("/v1/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.TEXT_EVENT_STREAM, MediaType.APPLICATION_JSON);
        authorize(spec);
        return spec.body(request).retrieve().toEntity(String.class);
    }

    public ResponseEntity<JsonNode> models() {
        var spec = restClient.get().uri("/v1/models").accept(MediaType.APPLICATION_JSON);
        authorize(spec);
        return spec.retrieve().toEntity(JsonNode.class);
    }

    private void authorize(RestClient.RequestHeadersSpec<?> spec) {
        if (!properties.getUpstreamApiKey().isBlank()) {
            spec.header(HttpHeaders.AUTHORIZATION, "Bearer " + properties.getUpstreamApiKey());
        }
    }
}
