package com.eap.platform.telemetry;

import com.eap.platform.common.ApiResponse;
import com.eap.platform.common.BusinessException;
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

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;

@Validated
@RestController
@RequestMapping("/api/telemetry")
public class ModelTelemetryController {
    private final ModelTelemetryService telemetryService;

    public ModelTelemetryController(ModelTelemetryService telemetryService) {
        this.telemetryService = telemetryService;
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
            @RequestParam(defaultValue = "100") @Min(1) @Max(500) int limit
    ) {
        return ApiResponse.ok(telemetryService.list(limit));
    }

    @GetMapping("/physical-summary")
    public ApiResponse<ModelTelemetryDtos.PhysicalTelemetrySummary> summary(
            @RequestParam(defaultValue = "24") @Min(1) @Max(8760) int hours
    ) {
        return ApiResponse.ok(telemetryService.summary(hours));
    }

    private void requireIngestionToken(String token) {
        String configured = telemetryService.ingestionToken();
        if (configured.isBlank()) {
            return;
        }
        if (token == null || !MessageDigest.isEqual(
                configured.getBytes(StandardCharsets.UTF_8),
                token.getBytes(StandardCharsets.UTF_8)
        )) {
            throw new BusinessException("模型遥测上报凭证无效");
        }
    }
}
