package com.eap.platform.evaluation;

import com.eap.platform.common.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Validated
@RestController
@RequestMapping("/api/evaluations")
public class EvaluationController {
    private final EvaluationService service;
    private final EvaluationProjectionService projectionService;
    private final EvaluationJobService jobService;

    public EvaluationController(EvaluationService service, EvaluationProjectionService projectionService,
                                EvaluationJobService jobService) {
        this.service = service;
        this.projectionService = projectionService;
        this.jobService = jobService;
    }

    @PostMapping("/evaluators")
    public ApiResponse<EvaluationDtos.EvaluatorResponse> createEvaluator(
            @Valid @RequestBody EvaluationDtos.EvaluatorCreateRequest request
    ) {
        return ApiResponse.ok(service.createEvaluator(request));
    }

    @GetMapping("/evaluators")
    public ApiResponse<List<EvaluationDtos.EvaluatorResponse>> listEvaluators() {
        return ApiResponse.ok(service.listEvaluators());
    }

    @PostMapping("/datasets")
    public ApiResponse<EvaluationDtos.DatasetResponse> createDataset(
            @Valid @RequestBody EvaluationDtos.DatasetCreateRequest request
    ) {
        return ApiResponse.ok(service.createDataset(request));
    }

    @GetMapping("/datasets")
    public ApiResponse<List<EvaluationDtos.DatasetResponse>> listDatasets() {
        return ApiResponse.ok(service.listDatasets());
    }

    @PostMapping("/results")
    public ApiResponse<EvaluationDtos.EvaluationResultResponse> evaluate(
            @Valid @RequestBody EvaluationDtos.EvaluationRequest request
    ) {
        return ApiResponse.ok(service.evaluate(request));
    }

    @GetMapping("/results")
    public ApiResponse<List<EvaluationDtos.EvaluationResultResponse>> listResults(
            @RequestParam(required = false) Long agentId,
            @RequestParam(required = false) String traceId,
            @RequestParam(defaultValue = "100") @Min(1) @Max(500) int limit
    ) {
        return ApiResponse.ok(service.listResults(agentId, traceId, limit));
    }

    @GetMapping("/summary")
    public ApiResponse<EvaluationDtos.EvaluationSummaryResponse> summary(
            @RequestParam(defaultValue = "24") @Min(1) @Max(720) int hours
    ) {
        return ApiResponse.ok(service.summary(hours));
    }

    @PostMapping("/synchronize")
    public ApiResponse<EvaluationDtos.EvaluationSyncResponse> synchronize(
            @RequestParam(defaultValue = "200") @Min(1) @Max(500) int limit
    ) {
        return ApiResponse.ok(projectionService.synchronize(limit));
    }

    @PostMapping("/jobs")
    public ApiResponse<EvaluationDtos.EvaluationJobResponse> submitJob(
            @RequestParam(defaultValue = "200") @Min(1) @Max(500) int limit
    ) {
        return ApiResponse.ok(jobService.submit(limit));
    }

    @GetMapping("/jobs")
    public ApiResponse<List<EvaluationDtos.EvaluationJobResponse>> listJobs() {
        return ApiResponse.ok(jobService.list());
    }

    @GetMapping("/jobs/{id}")
    public ApiResponse<EvaluationDtos.EvaluationJobResponse> getJob(@org.springframework.web.bind.annotation.PathVariable Long id) {
        return ApiResponse.ok(jobService.get(id));
    }
}
