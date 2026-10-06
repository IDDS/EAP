package com.eap.platform.monitoring;
import com.eap.platform.common.ApiResponse;
import org.springframework.web.bind.annotation.*;
@RestController
@RequestMapping("/api/substrate/monitoring")
public class SubstrateMonitoringController {
    private final SubstrateMonitoringService service;
    public SubstrateMonitoringController(SubstrateMonitoringService service){this.service=service;}
    @GetMapping public ApiResponse<SubstrateMonitoringService.Snapshot> get(@RequestParam(defaultValue="60") int minutes,@RequestParam(defaultValue="RUNTIME") String backend){return ApiResponse.ok(service.snapshot(minutes,backend));}
}
