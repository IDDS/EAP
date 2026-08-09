package com.eap.platform.observability;

import com.eap.platform.common.ApiResponse;
import com.eap.platform.common.BusinessException;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

@RestController
@RequestMapping("/api/observability/otlp")
public class OtlpTraceIngestionController {
    private final OtlpTraceIngestionService service;
    private final ContentObservationService contentObservationService;

    public OtlpTraceIngestionController(
            OtlpTraceIngestionService service,
            ContentObservationService contentObservationService
    ) {
        this.service = service;
        this.contentObservationService = contentObservationService;
    }

    @PostMapping("/v1/traces")
    public ApiResponse<OtlpTraceIngestionDtos.IngestionResponse> ingest(
            @RequestHeader(value = "X-EAP-Observability-Token", required = false) String token,
            @RequestBody JsonNode payload
    ) {
        requireIngestionToken(token);
        return ApiResponse.ok(service.ingest(payload));
    }

    private void requireIngestionToken(String token) {
        String configured = contentObservationService.ingestionToken();
        if (configured.isBlank()) return;
        if (token == null || !MessageDigest.isEqual(
                configured.getBytes(StandardCharsets.UTF_8), token.getBytes(StandardCharsets.UTF_8)
        )) {
            throw new BusinessException("OTLP 深度观测上报凭证无效");
        }
    }
}
