package com.eap.platform.platform;

import com.eap.platform.common.ApiResponse;
import com.eap.platform.config.PlatformPermission;
import com.eap.platform.config.SecurityProperties;
import com.eap.platform.control.ControlDispatchProperties;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.Map;

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
                Instant.now(),
                securityProperties.getOidcClientId().isEmpty() ? null : new OidcConfig(
                        securityProperties.getIssuerUri(), securityProperties.getOidcClientId(), securityProperties.getOidcScopes())
        ));
    }

    @GetMapping("/me")
    public ApiResponse<CurrentUser> me(Authentication authentication) {
        List<String> roles = authentication == null ? List.of() : authentication.getAuthorities().stream()
                .map(authority -> authority.getAuthority())
                .filter(authority -> authority.startsWith("ROLE_"))
                .map(authority -> authority.substring(5)).distinct().sorted().toList();
        return ApiResponse.ok(new CurrentUser(
                securityProperties.getMode().name(),
                authentication == null ? null : authentication.getName(),
                roles, PlatformPermission.capabilities(authentication, securityProperties.isDevelopmentMode())
        ));
    }

    public record CurrentUser(String mode, String subject, List<String> roles, Map<String, Boolean> capabilities) {}

    public record OidcConfig(String issuer, String clientId, String scopes) {}

    public record PlatformStatus(
            String securityMode,
            boolean controlDispatchEnabled,
            String controlDispatchMode,
            Instant generatedAt,
            OidcConfig oidc
    ) {}
}
