package com.eap.platform.discovery;

import com.eap.platform.common.ApiResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import java.util.LinkedHashMap;
import java.util.Map;

@RestController
public class ContentAnalysisController {
    private final BusinessContentRecordRepository repository;
    private final boolean enabled;

    public ContentAnalysisController(BusinessContentRecordRepository repository,
            @Value("${eap.observability.content-analysis-enabled:true}") boolean enabled) {
        this.repository = repository;
        this.enabled = enabled;
    }

    @GetMapping("/api/observability/content-analysis")
    public ApiResponse<Status> status() {
        Map<String, Long> counts = new LinkedHashMap<>();
        repository.projectionCounts().forEach(item -> counts.put(item.getStatus(), item.getTotal()));
        return ApiResponse.ok(new Status(enabled, counts));
    }

    public record Status(boolean enabled, Map<String, Long> counts) {}
}
