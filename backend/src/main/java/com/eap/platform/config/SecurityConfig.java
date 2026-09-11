package com.eap.platform.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtDecoders;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {
    private final SecurityProperties properties;

    public SecurityConfig(SecurityProperties properties) {
        this.properties = properties;
    }

    @Bean
    @ConditionalOnProperty(name = "eap.security.mode", havingValue = "DEVELOPMENT", matchIfMissing = true)
    SecurityFilterChain developmentSecurityFilterChain(HttpSecurity http) throws Exception {
        http.csrf(csrf -> csrf.disable())
                .cors(Customizer.withDefaults())
                .authorizeHttpRequests(auth -> auth
                        .anyRequest().permitAll())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS));
        return http.build();
    }

    @Bean
    @ConditionalOnProperty(name = "eap.security.mode", havingValue = "JWT")
    SecurityFilterChain jwtSecurityFilterChain(HttpSecurity http, JwtAuthenticationConverter jwtAuthenticationConverter) throws Exception {
        http.csrf(csrf -> csrf.disable())
                .cors(Customizer.withDefaults())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health", "/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
                        .requestMatchers("/api/platform/status").permitAll()
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .requestMatchers("/api/discovery/v1/agent/**").permitAll()
                        // Machine-facing endpoints perform their own constant-time token validation.
                        .requestMatchers(HttpMethod.POST,
                                "/api/telemetry/model-invocations",
                                "/api/telemetry/provider-usage-snapshots",
                                "/api/knowledge/interactions",
                                "/api/knowledge/interactions/*/outcome",
                                "/api/management/value-events",
                                "/api/governance/preflight",
                                "/api/governance/data-events",
                                "/api/observability/content",
                                "/api/observability/otlp/v1/traces",
                                "/api/discovery/endpoints/register",
                                "/api/discovery/endpoints/observations",
                                "/api/discovery/connectors/microsoft-defender/ingest",
                                "/api/discovery/connectors/kubernetes/watch",
                                "/api/discovery/connectors/kubernetes/admission",
                                "/api/discovery/connectors/host/observations",
                                "/api/discovery/connectors/ebpf/observations").permitAll()
                        .requestMatchers("/api/gateway/v1/**").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/observability/content", "/api/observability/content/page")
                            .hasAnyRole("GOVERNANCE_ADMIN", "PLATFORM_ADMIN")
                        .requestMatchers(HttpMethod.POST, "/api/discovery/v1/admin/agents/*/content-approval")
                            .hasAnyRole("PLATFORM_ADMIN", "DATA_OWNER")
                        .requestMatchers(HttpMethod.GET, "/api/**").hasAnyRole("VIEWER", "OPERATOR", "GOVERNANCE_ADMIN", "PLATFORM_ADMIN")
                        .requestMatchers(HttpMethod.POST, "/api/evaluations/evaluators", "/api/evaluations/datasets", "/api/evaluations/datasets/*/items", "/api/evaluations/datasets/*/run", "/api/evaluations/results", "/api/evaluations/synchronize", "/api/evaluations/jobs")
                            .hasAnyRole("OPERATOR", "PLATFORM_ADMIN")
                        .requestMatchers(HttpMethod.PATCH, "/api/evaluations/datasets/*/items/*/annotation")
                            .hasAnyRole("OPERATOR", "GOVERNANCE_ADMIN", "PLATFORM_ADMIN")
                        .requestMatchers("/api/governance/**", "/api/policies/**").hasAnyRole("GOVERNANCE_ADMIN", "PLATFORM_ADMIN")
                        .requestMatchers("/api/control/**", "/api/workflows/**", "/api/orchestration/**", "/api/tasks/**")
                            .hasAnyRole("OPERATOR", "PLATFORM_ADMIN")
                        .requestMatchers("/api/management/connectors/**")
                            .hasAnyRole("OPERATOR", "PLATFORM_ADMIN")
                        .requestMatchers(HttpMethod.POST, "/api/agentops/**")
                            .hasAnyRole("OPERATOR", "PLATFORM_ADMIN")
                        .requestMatchers("/api/agents/**", "/api/registry/**", "/api/discovery/**")
                            .hasAnyRole("OPERATOR", "PLATFORM_ADMIN")
                        .requestMatchers("/api/**").authenticated()
                        .anyRequest().authenticated())
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter)));
        return http.build();
    }

    @Bean
    @ConditionalOnProperty(name = "eap.security.mode", havingValue = "JWT")
    JwtDecoder jwtDecoder() {
        if (!properties.isJwtMode()) {
            return jwt -> { throw new IllegalStateException("JWT 认证未启用"); };
        }
        if (properties.getIssuerUri().isBlank() && properties.getJwkSetUri().isBlank()) {
            throw new IllegalStateException("JWT 模式必须配置 EAP_SECURITY_ISSUER_URI 或 EAP_SECURITY_JWK_SET_URI");
        }
        JwtDecoder decoder = properties.getIssuerUri().isBlank()
                ? NimbusJwtDecoder.withJwkSetUri(properties.getJwkSetUri()).build()
                : JwtDecoders.fromIssuerLocation(properties.getIssuerUri());
        if (decoder instanceof NimbusJwtDecoder nimbus) {
            List<OAuth2TokenValidator<Jwt>> validators = new ArrayList<>();
            if (!properties.getIssuerUri().isBlank()) {
                validators.add(JwtValidators.createDefaultWithIssuer(properties.getIssuerUri()));
            } else {
                validators.add(JwtValidators.createDefault());
            }
            if (!properties.getAudience().isBlank()) {
                validators.add(new JwtClaimValidator<Collection<String>>("aud", audiences ->
                        audiences != null && audiences.contains(properties.getAudience())));
            }
            nimbus.setJwtValidator(new DelegatingOAuth2TokenValidator<>(validators));
        }
        return decoder;
    }

    @Bean
    JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(this::authorities);
        return converter;
    }

    private Collection<GrantedAuthority> authorities(Jwt jwt) {
        Set<String> authorities = new LinkedHashSet<>();
        addClaimAuthorities(readClaim(jwt.getClaims(), properties.getRolesClaim()), authorities);
        addClaimAuthorities(jwt.getClaims().get("scope"), authorities);
        addClaimAuthorities(jwt.getClaims().get("scp"), authorities);
        return authorities.stream()
                .map(value -> (GrantedAuthority) new SimpleGrantedAuthority(value))
                .toList();
    }

    private Object readClaim(Map<String, Object> claims, String path) {
        Object current = claims;
        for (String segment : path.split("\\.")) {
            if (!(current instanceof Map<?, ?> values)) return null;
            current = values.get(segment);
        }
        return current;
    }

    private void addClaimAuthorities(Object claim, Set<String> authorities) {
        if (claim instanceof Collection<?> values) {
            values.forEach(value -> addAuthority(String.valueOf(value), authorities));
            return;
        }
        if (claim instanceof String value) {
            for (String item : value.split("[ ,]+")) addAuthority(item, authorities);
        }
    }

    private void addAuthority(String value, Set<String> authorities) {
        if (value == null || value.isBlank()) return;
        String normalized = value.trim().toUpperCase()
                .replaceFirst("^ROLE_", "")
                .replaceAll("[^A-Z0-9]+", "_")
                .replaceAll("^_+|_+$", "");
        if (!normalized.isBlank()) authorities.add("ROLE_" + normalized);
    }

    @Bean
    CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(properties.allowedOriginList());
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("*"));
        configuration.setAllowCredentials(true);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }
}
