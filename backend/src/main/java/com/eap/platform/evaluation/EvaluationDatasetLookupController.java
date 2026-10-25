package com.eap.platform.evaluation;

import com.eap.platform.common.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/evaluations")
public class EvaluationDatasetLookupController {
    private final EvaluationDatasetLookupService service;

    public EvaluationDatasetLookupController(EvaluationDatasetLookupService service) { this.service = service; }

    // Query parameters preserve identifiers containing '/', '?', '#', or Unicode.
    // Both GET routes inherit PlatformPermission.VIEW_CONSOLE from SecurityConfig.
    @GetMapping("/datasets/lookup")
    public ApiResponse<EvaluationDtos.DatasetLookupResponse> dataset(@RequestParam String datasetKey) {
        return ApiResponse.ok(service.dataset(datasetKey));
    }

    @GetMapping("/dataset-items/lookup")
    public ApiResponse<EvaluationDtos.DatasetLookupResponse> sample(@RequestParam String datasetKey, @RequestParam String itemKey) {
        return ApiResponse.ok(service.sample(datasetKey, itemKey));
    }
}
