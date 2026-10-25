package com.eap.platform.observability;

import com.eap.platform.common.ApiResponse;
import org.springframework.web.bind.annotation.*;

/** Read-only metadata. Authorization uses the existing central VIEW_CONSOLE matcher. */
@RestController
@RequestMapping("/api/observability")
public class ObservationCoverageController {
    private final ObservationCoverageService coverage;
    private final RuntimePlacementEvidenceService placements;
    public ObservationCoverageController(ObservationCoverageService coverage, RuntimePlacementEvidenceService placements) {
        this.coverage = coverage;
        this.placements = placements;
    }
    @GetMapping("/coverage")
    public Object report(@RequestParam(defaultValue = "1440") int minutes) { return ApiResponse.ok(coverage.report(minutes)); }
    @GetMapping("/operations/{id}")
    public Object operation(@PathVariable long id) { return ApiResponse.ok(coverage.detail(id)); }
    @GetMapping("/runtime-placement-sampling")
    public Object sampling() { return ApiResponse.ok(placements.sampling()); }
}
