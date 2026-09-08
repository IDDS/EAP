package com.eap.platform.knowledge;

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
@RequestMapping("/api/knowledge")
public class KnowledgeController {
    private final KnowledgeService knowledgeService;
    private final MachineTokenValidator machineTokenValidator;

    public KnowledgeController(KnowledgeService knowledgeService, MachineTokenValidator machineTokenValidator) {
        this.knowledgeService = knowledgeService;
        this.machineTokenValidator = machineTokenValidator;
    }

    @PostMapping("/interactions")
    public ApiResponse<KnowledgeDtos.KnowledgeInteractionResponse> record(
            @RequestHeader(value = "X-EAP-Knowledge-Token", required = false) String token,
            @Valid @RequestBody KnowledgeDtos.KnowledgeInteractionRequest request
    ) {
        requireIngestionToken(token);
        return ApiResponse.ok(knowledgeService.record(request));
    }

    @GetMapping("/interactions")
    public ApiResponse<List<KnowledgeDtos.KnowledgeInteractionResponse>> list(
            @RequestParam(defaultValue = "100") @Min(1) @Max(500) int limit,
            @RequestParam(required = false) @Min(1) Long agentId,
            @RequestParam(defaultValue = "0") @Min(0) int page
    ) {
        return ApiResponse.ok(knowledgeService.list(limit, agentId, page));
    }

    @GetMapping("/interactions/{id}")
    public ApiResponse<KnowledgeDtos.KnowledgeInteractionResponse> get(@PathVariable Long id) {
        return ApiResponse.ok(knowledgeService.get(id));
    }

    @PostMapping("/interactions/{id}/outcome")
    public ApiResponse<KnowledgeDtos.KnowledgeInteractionResponse> outcome(
            @RequestHeader(value = "X-EAP-Knowledge-Token", required = false) String token,
            @PathVariable Long id,
            @Valid @RequestBody KnowledgeDtos.OutcomeRequest request
    ) {
        requireIngestionToken(token);
        return ApiResponse.ok(knowledgeService.updateOutcome(id, request));
    }

    @GetMapping("/summary")
    public ApiResponse<KnowledgeDtos.KnowledgeSummary> summary(
            @RequestParam(defaultValue = "24") @Min(1) @Max(8760) int hours
    ) {
        return ApiResponse.ok(knowledgeService.summary(hours));
    }

    private void requireIngestionToken(String token) {
        machineTokenValidator.require(knowledgeService.ingestionToken(), token, "知识遥测上报凭证");
    }
}
