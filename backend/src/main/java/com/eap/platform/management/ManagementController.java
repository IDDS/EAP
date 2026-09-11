package com.eap.platform.management;

import com.eap.platform.common.ApiResponse;
import com.eap.platform.config.MachineTokenValidator;
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

import java.util.List;

@Validated
@RestController
@RequestMapping("/api/management")
public class ManagementController {
    private final ManagementService managementService;
    private final MachineTokenValidator machineTokenValidator;

    public ManagementController(ManagementService managementService, MachineTokenValidator machineTokenValidator) {
        this.managementService = managementService;
        this.machineTokenValidator = machineTokenValidator;
    }

    @PostMapping("/value-events")
    public ApiResponse<ManagementDtos.ValueEventResponse> recordValue(
            @RequestHeader(value = "X-EAP-Management-Token", required = false) String token,
            @Valid @RequestBody ManagementDtos.ValueEventRequest request
    ) {
        requireIngestionToken(token);
        return ApiResponse.ok(managementService.recordValue(request));
    }

    @GetMapping("/value-events/page")
    public ApiResponse<com.eap.platform.common.HistoryPage<ManagementDtos.ValueEventResponse>> page(
            @Valid @org.springframework.web.bind.annotation.ModelAttribute com.eap.platform.common.EventHistoryQuery query) {
        return ApiResponse.ok(managementService.pageValues(query));
    }

    @GetMapping("/value-events")
    public ApiResponse<List<ManagementDtos.ValueEventResponse>> listValues(
            @RequestParam(defaultValue = "100") @Min(1) @Max(500) int limit
    ) {
        return ApiResponse.ok(managementService.listValues(limit));
    }

    @GetMapping("/summary")
    public ApiResponse<ManagementDtos.ManagementSummary> summary(
            @RequestParam(defaultValue = "24") @Min(1) @Max(8760) int hours,
            @RequestParam(required = false) @jakarta.validation.constraints.Size(max = 16) String currency
    ) {
        return ApiResponse.ok(managementService.summary(hours, currency));
    }

    private void requireIngestionToken(String token) {
        machineTokenValidator.require(managementService.ingestionToken(), token, "管理价值上报凭证");
    }
}
