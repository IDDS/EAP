package com.eap.platform.management;

import com.eap.platform.common.ApiResponse;
import com.eap.platform.common.BusinessException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
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
@RequestMapping("/api/management")
public class ManagementController {
    private final ManagementService managementService;

    public ManagementController(ManagementService managementService) {
        this.managementService = managementService;
    }

    @PostMapping("/value-events")
    public ApiResponse<ManagementDtos.ValueEventResponse> recordValue(
            @RequestHeader(value = "X-EAP-Management-Token", required = false) String token,
            @Valid @RequestBody ManagementDtos.ValueEventRequest request
    ) {
        requireIngestionToken(token);
        return ApiResponse.ok(managementService.recordValue(request));
    }

    @GetMapping("/value-events")
    public ApiResponse<List<ManagementDtos.ValueEventResponse>> listValues(
            @RequestParam(defaultValue = "100") @Min(1) @Max(500) int limit
    ) {
        return ApiResponse.ok(managementService.listValues(limit));
    }

    @GetMapping("/summary")
    public ApiResponse<ManagementDtos.ManagementSummary> summary(
            @RequestParam(defaultValue = "24") @Min(1) @Max(8760) int hours
    ) {
        return ApiResponse.ok(managementService.summary(hours));
    }

    private void requireIngestionToken(String token) {
        String configured = managementService.ingestionToken();
        if (configured.isBlank()) {
            return;
        }
        if (token == null || !MessageDigest.isEqual(
                configured.getBytes(StandardCharsets.UTF_8),
                token.getBytes(StandardCharsets.UTF_8)
        )) {
            throw new BusinessException("管理价值上报凭证无效");
        }
    }
}
