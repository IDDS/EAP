package com.eap.platform.config;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class JwtAuthorityMappingTests {

    @Test
    void mapsNestedRolesAndScopesWithoutCaseSensitivity() {
        SecurityProperties properties = new SecurityProperties();
        properties.setRolesClaim("realm_access.roles");
        SecurityConfig config = new SecurityConfig(properties, org.mockito.Mockito.mock(ProjectIsolation.class));
        Jwt jwt = new Jwt(
                "token",
                Instant.now(),
                Instant.now().plusSeconds(300),
                Map.of("alg", "none"),
                Map.of(
                        "realm_access", Map.of("roles", List.of("viewer", "governance-admin")),
                        "scope", "operator"
                )
        );

        var authentication = config.jwtAuthenticationConverter().convert(jwt);

        assertThat(authentication).isNotNull();
        assertThat(authentication.getAuthorities())
                .extracting("authority")
                .containsExactlyInAnyOrder("ROLE_VIEWER", "ROLE_GOVERNANCE_ADMIN", "ROLE_OPERATOR");
    }
}
