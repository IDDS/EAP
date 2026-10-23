package com.eap.platform.observability;

import com.eap.platform.common.ApiResponse;
import com.eap.platform.config.MachineTokenValidator;
import com.eap.platform.config.SecurityProperties;
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

import java.time.Instant;
import java.util.List;

@Validated
@RestController
@RequestMapping("/api/observability")
public class ContentObservationController {
    private final ContentObservationService service;
    private final MachineTokenValidator machineTokenValidator;
    private final SecurityProperties securityProperties;

    public ContentObservationController(
            ContentObservationService service,
            MachineTokenValidator machineTokenValidator,
            SecurityProperties securityProperties
    ) {
        this.service = service;
        this.machineTokenValidator = machineTokenValidator;
        this.securityProperties = securityProperties;
    }

    @GetMapping("/content/page")
    public ApiResponse<com.eap.platform.common.HistoryPage<ContentObservationDtos.ContentObservationResponse>> page(
            @jakarta.validation.Valid @org.springframework.web.bind.annotation.ModelAttribute com.eap.platform.common.ObservationHistoryQuery query) {
        var page=service.page(query);
        if(securityProperties.isDevelopmentMode()) page=new com.eap.platform.common.HistoryPage<>(page.items().stream().map(ContentObservationDtos.ContentObservationResponse::withoutContent).toList(),page.totalElements(),page.page(),page.size());
        return ApiResponse.ok(page);
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
            @RequestParam(required = false) Instant to,
            @RequestParam(defaultValue = "0") @jakarta.validation.constraints.Min(0) int page
    ) {
        List<ContentObservationDtos.ContentObservationResponse> observations =
                service.list(limit, agentId, endpointKey, traceId, direction, from, to, page);
        if (securityProperties.isDevelopmentMode()) {
            observations = observations.stream()
                    .map(ContentObservationDtos.ContentObservationResponse::withoutContent)
                    .toList();
        }
        return ApiResponse.ok(observations);
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
