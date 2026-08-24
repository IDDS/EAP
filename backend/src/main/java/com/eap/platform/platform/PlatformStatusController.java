package com.eap.platform.platform;

import com.eap.platform.common.ApiResponse;
import com.eap.platform.config.SecurityProperties;
import com.eap.platform.control.ControlDispatchProperties;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

/**
 * Safe, non-secret runtime facts used to keep the console honest about its environment.
 */
@RestController
@RequestMapping("/api/platform")
public class PlatformStatusController {
    private final SecurityProperties securityProperties;
    private final ControlDispatchProperties controlDispatchProperties;

    public PlatformStatusController(
            SecurityProperties securityProperties,
            ControlDispatchProperties controlDispatchProperties
    ) {
        this.securityProperties = securityProperties;
        this.controlDispatchProperties = controlDispatchProperties;
    }

    @GetMapping("/status")
    public ApiResponse<PlatformStatus> status() {
        return ApiResponse.ok(new PlatformStatus(
                securityProperties.getMode().name(),
                controlDispatchProperties.isEnabled(),
                controlDispatchProperties.isEnabled() ? "HTTP_RUNTIME" : "CONTROL_PLANE_OUTBOX",
                Instant.now()
        ));
    }

    public record PlatformStatus(
            String securityMode,
            boolean controlDispatchEnabled,
            String controlDispatchMode,
            Instant generatedAt
    ) {}
}
