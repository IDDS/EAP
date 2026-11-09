package com.eap.platform.platform;

import com.eap.platform.common.ApiResponse;
import com.eap.platform.config.RevokedAccessTokens;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/platform/session")
public class PlatformSessionController {
    private final RevokedAccessTokens revocations;

    public PlatformSessionController(RevokedAccessTokens revocations) { this.revocations = revocations; }

    @PostMapping("/logout")
    public ApiResponse<Logout> logout(Authentication authentication) {
        if (!(authentication instanceof JwtAuthenticationToken jwt)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }
        revocations.revoke(jwt.getToken());
        return ApiResponse.ok(new Logout(true));
    }

    public record Logout(boolean revoked) {}
}
