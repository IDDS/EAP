package com.eap.platform.observability;

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
@RequestMapping("/api/observability")
public class ContentObservationController {
    private final ContentObservationService service;

    public ContentObservationController(ContentObservationService service) {
        this.service = service;
    }

    @GetMapping("/status")
    public ApiResponse<ContentObservationDtos.ObservationStatus> status() {
        return ApiResponse.ok(service.status());
    }

    @GetMapping("/content")
    public ApiResponse<List<ContentObservationDtos.ContentObservationResponse>> list(
            @RequestParam(defaultValue = "100") @Min(1) @Max(500) int limit
    ) {
        return ApiResponse.ok(service.list(limit));
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
        String configured = service.ingestionToken();
        if (configured.isBlank()) {
            return;
        }
        if (token == null || !MessageDigest.isEqual(
                configured.getBytes(StandardCharsets.UTF_8),
                token.getBytes(StandardCharsets.UTF_8)
        )) {
            throw new BusinessException("深度观测上报凭证无效");
        }
    }
}
