package com.eap.platform.governance;

import com.eap.platform.common.ApiResponse;
import com.eap.platform.config.MachineTokenValidator;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Validated
@RestController
@RequestMapping("/api/governance")
public class GovernanceDataController {
    private final GovernanceDataService service;
    private final GovernancePreflightService preflightService;
    private final MachineTokenValidator machineTokenValidator;

    public GovernanceDataController(
            GovernanceDataService service,
            GovernancePreflightService preflightService,
            MachineTokenValidator machineTokenValidator
    ) {
        this.service = service;
        this.preflightService = preflightService;
        this.machineTokenValidator = machineTokenValidator;
    }

    @PostMapping("/preflight")
    public ApiResponse<GovernanceDataDtos.PreflightResponse> preflight(
            @RequestHeader(value = "X-EAP-Governance-Token", required = false) String token,
            @Valid @RequestBody GovernanceDataDtos.PreflightRequest request
    ) {
        requireIngestionToken(token);
        return ApiResponse.ok(preflightService.evaluate(request));
    }

    @GetMapping("/preflight-decisions")
    public ApiResponse<List<GovernanceDataDtos.PreflightResponse>> decisions(
            @RequestParam(defaultValue = "100") @Min(1) @Max(500) int limit
    ) {
        return ApiResponse.ok(preflightService.list(limit));
    }

    @GetMapping("/enforcement-status")
    public ApiResponse<GovernanceDataDtos.EnforcementStatus> enforcementStatus() {
        return ApiResponse.ok(preflightService.status());
    }

    @PostMapping("/preflight-decisions/{decisionKey}/approve")
    public ApiResponse<GovernanceDataDtos.PreflightResponse> approve(
            @PathVariable String decisionKey,
            @RequestHeader(value = "X-EAP-Governance-Approval-Token", required = false) String approvalToken,
            @Valid @RequestBody GovernanceDataDtos.ApprovalRequest request
    ) {
        requireApprovalToken(approvalToken);
        return ApiResponse.ok(preflightService.approve(decisionKey, request));
    }

    @PostMapping("/preflight-decisions/{decisionKey}/reject")
    public ApiResponse<GovernanceDataDtos.PreflightResponse> reject(
            @PathVariable String decisionKey,
            @RequestHeader(value = "X-EAP-Governance-Approval-Token", required = false) String approvalToken,
            @Valid @RequestBody GovernanceDataDtos.ApprovalRequest request
    ) {
        requireApprovalToken(approvalToken);
        return ApiResponse.ok(preflightService.reject(decisionKey, request));
    }

    @PostMapping("/data-events")
    public ApiResponse<GovernanceDataDtos.DataEventResponse> record(
            @RequestHeader(value = "X-EAP-Governance-Token", required = false) String token,
            @Valid @RequestBody GovernanceDataDtos.DataEventRequest request
    ) {
        requireIngestionToken(token);
        return ApiResponse.ok(service.record(request));
    }

    @GetMapping("/data-events")
    public ApiResponse<List<GovernanceDataDtos.DataEventResponse>> list(
            @RequestParam(defaultValue = "100") @Min(1) @Max(500) int limit,
            @RequestParam(required = false) @Min(1) Long agentId,
            @RequestParam(defaultValue = "0") @Min(0) int page
    ) {
        return ApiResponse.ok(service.list(limit, agentId, page));
    }

    @GetMapping("/data-summary")
    public ApiResponse<GovernanceDataDtos.GovernanceSummary> summary(
            @RequestParam(defaultValue = "24") @Min(1) @Max(8760) int hours
    ) {
        return ApiResponse.ok(service.summary(hours));
    }

    private void requireIngestionToken(String token) {
        machineTokenValidator.require(service.ingestionToken(), token, "治理数据上报凭证");
    }

    private void requireApprovalToken(String token) {
        machineTokenValidator.require(service.approvalToken(), token, "治理审批凭证");
    }
}
