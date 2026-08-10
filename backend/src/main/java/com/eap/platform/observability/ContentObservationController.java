package com.eap.platform.observability;

import com.eap.platform.common.ApiResponse;
import com.eap.platform.config.MachineTokenValidator;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.time.Instant;

@Validated
@RestController
@RequestMapping("/api/observability")
public class ContentObservationController {
    private final ContentObservationService service;
    private final MachineTokenValidator machineTokenValidator;

    public ContentObservationController(ContentObservationService service, MachineTokenValidator machineTokenValidator) {
        this.service = service;
        this.machineTokenValidator = machineTokenValidator;
    }

    @GetMapping("/status")
    public ApiResponse<ContentObservationDtos.ObservationStatus> status() {
        return ApiResponse.ok(service.status());
    }

    @GetMapping("/content")
    public ApiResponse<List<ContentObservationDtos.ContentObservationResponse>> list(
            @RequestParam(defaultValue = "100") @Min(1) @Max(500) int limit,
            @RequestParam(required = false) Long agentId,
            @RequestParam(required = false) String endpointKey,
            @RequestParam(required = false) String traceId,
            @RequestParam(required = false) ContentDirection direction,
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to
    ) {
        return ApiResponse.ok(service.list(limit, agentId, endpointKey, traceId, direction, from, to));
    }

    @PostMapping("/content")
    public ApiResponse<ContentObservationDtos.ContentObservationResponse> capture(
            @RequestHeader(value = "X-EAP-Observability-Token", required = false) String token,
            @Valid @RequestBody ContentObservationDtos.CaptureRequest request
    ) {
        requireIngestionToken(token);
        return ApiResponse.ok(service.capture(request));
    }

    private void requireIngestionToken(String token) {
        machineTokenValidator.require(service.ingestionToken(), token, "深度观测上报凭证");
    }
}
