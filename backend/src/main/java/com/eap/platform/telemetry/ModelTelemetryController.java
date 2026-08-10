package com.eap.platform.telemetry;

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
@RequestMapping("/api/telemetry")
public class ModelTelemetryController {
    private final ModelTelemetryService telemetryService;
    private final MachineTokenValidator machineTokenValidator;

    public ModelTelemetryController(ModelTelemetryService telemetryService, MachineTokenValidator machineTokenValidator) {
        this.telemetryService = telemetryService;
        this.machineTokenValidator = machineTokenValidator;
    }

    @PostMapping("/model-invocations")
    public ApiResponse<ModelTelemetryDtos.ModelInvocationResponse> record(
            @RequestHeader(value = "X-EAP-Telemetry-Token", required = false) String token,
            @Valid @RequestBody ModelTelemetryDtos.ModelInvocationRequest request
    ) {
        requireIngestionToken(token);
        return ApiResponse.ok(telemetryService.record(request));
    }

    @GetMapping("/model-invocations")
    public ApiResponse<List<ModelTelemetryDtos.ModelInvocationResponse>> list(
            @RequestParam(defaultValue = "100") @Min(1) @Max(500) int limit,
            @RequestParam(required = false) Long agentId,
            @RequestParam(required = false) String endpointKey,
            @RequestParam(required = false) String runtimeInstanceKey,
            @RequestParam(required = false) String traceId,
            @RequestParam(required = false) ModelInvocationStatus status,
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to
    ) {
        return ApiResponse.ok(telemetryService.list(limit, agentId, endpointKey, runtimeInstanceKey, traceId, status, from, to));
    }

    @GetMapping("/physical-summary")
    public ApiResponse<ModelTelemetryDtos.PhysicalTelemetrySummary> summary(
            @RequestParam(defaultValue = "24") @Min(1) @Max(8760) int hours
    ) {
        return ApiResponse.ok(telemetryService.summary(hours));
    }

    private void requireIngestionToken(String token) {
        machineTokenValidator.require(telemetryService.ingestionToken(), token, "模型遥测上报凭证");
    }
}
