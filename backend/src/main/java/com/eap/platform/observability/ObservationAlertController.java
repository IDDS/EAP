package com.eap.platform.observability;

import com.eap.platform.common.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/observability/operations/{operation}/alerts")
public class ObservationAlertController {
    private final ObservationAlertService alerts;
    public ObservationAlertController(ObservationAlertService alerts) { this.alerts = alerts; }
    @GetMapping public Object inspect(@PathVariable long operation) { return ApiResponse.ok(alerts.inspect(operation)); }
    @PostMapping("/reconcile") public Object reconcile(@PathVariable long operation, Authentication auth) {
        return ApiResponse.ok(alerts.reconcile(operation, principal(auth)));
    }
    @PostMapping("/{id}/acknowledgement") public Object acknowledge(@PathVariable long operation, @PathVariable long id,
            @Valid @RequestBody ObservationAlertService.Acknowledge request, Authentication auth) {
        return ApiResponse.ok(alerts.acknowledge(operation, id, request, principal(auth)));
    }
    private static String principal(Authentication auth) { return auth == null ? "console" : auth.getName(); }
}
