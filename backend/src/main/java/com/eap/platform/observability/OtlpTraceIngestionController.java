package com.eap.platform.observability;

import com.eap.platform.common.ApiResponse;
import com.eap.platform.config.MachineTokenValidator;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/observability/otlp")
public class OtlpTraceIngestionController {
    private final OtlpTraceIngestionService service;
    private final ContentObservationService contentObservationService;
    private final MachineTokenValidator machineTokenValidator;

    public OtlpTraceIngestionController(
            OtlpTraceIngestionService service,
            ContentObservationService contentObservationService,
            MachineTokenValidator machineTokenValidator
    ) {
        this.service = service;
        this.contentObservationService = contentObservationService;
        this.machineTokenValidator = machineTokenValidator;
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
        machineTokenValidator.require(configured, token, "OTLP 深度观测上报凭证");
    }
}
