package com.eap.platform.observability;

import com.eap.platform.common.ApiResponse;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/observability/logs")
public class ObservationLogSearchController {
    private final ObservationLogSearchService logs;
    public ObservationLogSearchController(ObservationLogSearchService logs) { this.logs = logs; }
    @GetMapping public Object search(@RequestParam(defaultValue = "") String query,
            @RequestParam(defaultValue = "1440") int minutes, @RequestParam(defaultValue = "50") int limit) {
        return ApiResponse.ok(logs.search(query, minutes, limit));
    }
}
