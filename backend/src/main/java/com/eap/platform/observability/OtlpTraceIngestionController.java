package com.eap.platform.observability;

import com.eap.platform.common.ApiResponse;
import com.eap.platform.config.MachineTokenValidator;
import com.fasterxml.jackson.databind.JsonNode;
import io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceResponse;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

@org.springframework.validation.annotation.Validated
@RestController
@RequestMapping("/api/observability/otlp")
public class OtlpTraceIngestionController {
    private final OtlpTraceIngestionService service;
    private final ContentObservationService contentObservationService;
    private final MachineTokenValidator machineTokenValidator;
    private final OtlpProtobufAdapter protobufAdapter;

    public OtlpTraceIngestionController(
            OtlpTraceIngestionService service,
            ContentObservationService contentObservationService,
            MachineTokenValidator machineTokenValidator,
            OtlpProtobufAdapter protobufAdapter
    ) {
        this.service = service;
        this.contentObservationService = contentObservationService;
        this.machineTokenValidator = machineTokenValidator;
        this.protobufAdapter = protobufAdapter;
    }

    @PostMapping(value = "/v1/traces", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ApiResponse<OtlpTraceIngestionDtos.IngestionResponse> ingest(
            @RequestHeader(value = "X-EAP-Observability-Token", required = false) String token,
            @RequestBody JsonNode payload
    ) {
        requireIngestionToken(token);
        return ApiResponse.ok(service.ingest(payload));
    }

    @PostMapping(
            value = "/v1/traces",
            consumes = {"application/x-protobuf", "application/protobuf", MediaType.APPLICATION_OCTET_STREAM_VALUE},
            produces = "application/x-protobuf"
    )
    public ResponseEntity<byte[]> ingestProtobuf(
            @RequestHeader(value = "X-EAP-Observability-Token", required = false) String token,
            @RequestBody byte[] payload
    ) {
        requireIngestionToken(token);
        service.ingest(protobufAdapter.toJson(payload));
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("application/x-protobuf"))
                .body(ExportTraceServiceResponse.getDefaultInstance().toByteArray());
    }

    @GetMapping("/spans/page")
    public ApiResponse<com.eap.platform.common.HistoryPage<OtlpTraceIngestionDtos.TraceSpanResponse>> page(
            @jakarta.validation.Valid @org.springframework.web.bind.annotation.ModelAttribute com.eap.platform.common.ObservationHistoryQuery query) {
        return ApiResponse.ok(service.page(query));
    }

    @GetMapping("/spans")
    public ApiResponse<java.util.List<OtlpTraceIngestionDtos.TraceSpanResponse>> spans(
            @org.springframework.web.bind.annotation.RequestParam(defaultValue = "100") @jakarta.validation.constraints.Min(1) @jakarta.validation.constraints.Max(500) int limit,
            @org.springframework.web.bind.annotation.RequestParam(required = false) Long agentId,
            @org.springframework.web.bind.annotation.RequestParam(required = false) String endpointKey,
            @org.springframework.web.bind.annotation.RequestParam(required = false) String traceId,
            @org.springframework.web.bind.annotation.RequestParam(required = false) TraceSpanKind spanKind,
            @org.springframework.web.bind.annotation.RequestParam(required = false) Instant from,
            @org.springframework.web.bind.annotation.RequestParam(required = false) Instant to,
            @org.springframework.web.bind.annotation.RequestParam(defaultValue = "0") @jakarta.validation.constraints.Min(0) int page
    ) {
        return ApiResponse.ok(service.listTraceSpans(limit, agentId, endpointKey, traceId, spanKind, from, to, page));
    }

    private void requireIngestionToken(String token) {
        String configured = contentObservationService.ingestionToken();
        machineTokenValidator.require(configured, token, "OTLP 深度观测上报凭证");
    }
}
