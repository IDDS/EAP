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
    private final ProjectIsolation projectIsolation;

    public SecurityConfig(SecurityProperties properties, ProjectIsolation projectIsolation) {
        this.properties = properties;
        this.projectIsolation = projectIsolation;
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
                        // Any signed-in user may ask what they are allowed to do, even with no EAP role yet.
                        .requestMatchers(HttpMethod.GET, "/api/platform/me").authenticated()
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
                        .requestMatchers("/internal/agent-optimizations/**").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/team-evolutions/*/proposals/*/content")
                            .hasAnyRole(PlatformPermission.VIEW_CONTENT.roles())
                        .requestMatchers(HttpMethod.GET, "/api/team-evolutions/*/proposals", "/api/team-evolutions/*/proposals/*")
                            .hasAnyRole(PlatformPermission.RUN_AGENT_EXPERIMENTS.roles())
                        .requestMatchers(HttpMethod.POST, "/api/team-evolutions/*/proposals")
                            .hasAnyRole(PlatformPermission.RUN_AGENT_EXPERIMENTS.roles())
                        .requestMatchers("/api/team-evolutions/*/proposals", "/api/team-evolutions/*/proposals/**").denyAll()
                        .requestMatchers(HttpMethod.GET, "/api/team-evolutions/receipts", "/api/team-evolutions/*/actions")
                            .hasAnyRole(PlatformPermission.RUN_AGENT_EXPERIMENTS.roles())
                        .requestMatchers("/api/team-evolutions/receipts", "/api/team-evolutions/receipts/**", "/api/team-evolutions/*/actions", "/api/team-evolutions/*/actions/**").denyAll()
                        .requestMatchers(HttpMethod.GET, "/api/team-evolutions/console", "/api/team-evolutions/*/console")
                            .hasAnyRole(PlatformPermission.VIEW_CONSOLE.roles())
                        .requestMatchers("/api/team-evolutions/console", "/api/team-evolutions/console/**", "/api/team-evolutions/*/console", "/api/team-evolutions/*/console/**").denyAll()
                        .requestMatchers(HttpMethod.GET, "/api/observability/content", "/api/observability/content/page",
                                "/api/workflows/*/executions/*/outputs", "/api/ax/tasks/*/content", "/api/agent-experiments/*/content", "/api/agent-optimizations/*/content", "/api/team-evolutions/*/content")
                            .hasAnyRole(PlatformPermission.VIEW_CONTENT.roles())
                        .requestMatchers(HttpMethod.POST, "/api/discovery/v1/admin/agents/*/content-approval")
                            .hasAnyRole(PlatformPermission.APPROVE_CONTENT.roles())
                         .requestMatchers(HttpMethod.POST, "/api/agent-experiments", "/api/agent-experiments/profile", "/api/agent-optimizations", "/api/team-evolutions", "/api/team-evolutions/*/candidates", "/api/team-evolutions/*/evaluations", "/api/team-evolutions/*/budget", "/api/team-evolutions/*/budget/check", "/api/team-evolutions/*/evaluations/*/execution", "/api/team-evolutions/*/evaluations/*/score", "/api/team-evolutions/*/frontier", "/api/team-evolutions/*/holdout", "/api/team-evolutions/*/closure")
                            .hasAnyRole(PlatformPermission.RUN_AGENT_EXPERIMENTS.roles())
                        .requestMatchers(HttpMethod.POST, "/api/agent-optimizations/*/unknown-review")
                            .hasAnyRole(PlatformPermission.REVIEW_EXPERIMENT_UNKNOWNS.roles())
                        .requestMatchers(HttpMethod.POST, "/api/observability/diagnostics/policy")
                            .hasAnyRole(PlatformPermission.GOVERNANCE.roles())
                        .requestMatchers(HttpMethod.GET, "/api/observability/agent-executions", "/api/observability/agent-executions/*/graph", "/api/observability/agent-executions/*/links", "/api/observability/agent-executions/deliveries")
                            .hasAnyRole(PlatformPermission.VIEW_CONSOLE.roles())
                        .requestMatchers("/api/observability/agent-executions", "/api/observability/agent-executions/**").denyAll()
                        .requestMatchers(HttpMethod.GET, "/api/observability/logs")
                            .hasAnyRole(PlatformPermission.VIEW_CONSOLE.roles())
                        .requestMatchers("/api/observability/logs", "/api/observability/logs/**").denyAll()
                        .requestMatchers(HttpMethod.GET, "/api/observability/operations/*/alerts")
                            .hasAnyRole(PlatformPermission.VIEW_CONSOLE.roles())
                        .requestMatchers(HttpMethod.POST, "/api/observability/operations/*/alerts/reconcile",
                                "/api/observability/operations/*/alerts/*/acknowledgement")
                            .hasAnyRole(PlatformPermission.GOVERNANCE.roles())
                        .requestMatchers("/api/observability/operations/*/alerts/**").denyAll()
                        .requestMatchers(HttpMethod.POST, "/api/learning-feedbacks/*/status")
                            .hasAnyRole(PlatformPermission.VIEW_CONTENT.roles())
                        .requestMatchers(HttpMethod.POST, "/api/learning-feedbacks")
                            .hasAnyRole(PlatformPermission.MANAGE_FEEDBACK.roles())
                        .requestMatchers(HttpMethod.GET, "/api/safety/summary", "/api/safety/sessions",
                                "/api/safety/sessions/*/events", "/api/safety/sessions/*/artifacts")
                            .hasAnyRole(PlatformPermission.VIEW_CONSOLE.roles())
                        .requestMatchers(HttpMethod.POST, "/api/safety/sessions", "/api/safety/events/evaluate", "/api/safety/artifacts")
                            .hasAnyRole(PlatformPermission.MANAGE_SAFETY.roles())
                        .requestMatchers("/api/safety", "/api/safety/**").denyAll()
                        .requestMatchers(HttpMethod.GET, "/api/agents/*/substrate/instances/*/chat/*")
                            .hasAnyRole(PlatformPermission.OPERATE_INSTANCES.roles())
                        .requestMatchers(HttpMethod.GET, "/api/agents/*/substrate/deployments/requests/*")
                            .hasAnyRole(PlatformPermission.DEPLOY_AGENTS.roles())
                        .requestMatchers(HttpMethod.GET, "/api/agents/*/substrate/deployments/*/content")
                            .hasAnyRole(PlatformPermission.VIEW_CONTENT.roles())
                        .requestMatchers(HttpMethod.GET, "/api/**").hasAnyRole(PlatformPermission.VIEW_CONSOLE.roles())
                        .requestMatchers(HttpMethod.POST, "/api/evaluations/evaluators", "/api/evaluations/datasets", "/api/evaluations/datasets/*/items", "/api/evaluations/datasets/*/run", "/api/evaluations/results", "/api/evaluations/synchronize", "/api/evaluations/jobs")
                            .hasAnyRole(PlatformPermission.MANAGE_EVALUATIONS.roles())
                        .requestMatchers(HttpMethod.PATCH, "/api/evaluations/datasets/*/items/*/annotation")
                            .hasAnyRole(PlatformPermission.ANNOTATE_EVALUATIONS.roles())
                        .requestMatchers(HttpMethod.POST, "/api/registry/runtime-tools/scopes", "/api/registry/runtime-tools/scopes/revoke").hasAnyRole(PlatformPermission.GOVERNANCE.roles())
                        .requestMatchers(HttpMethod.PUT, "/api/registry/runtime-tools/policy").hasAnyRole(PlatformPermission.GOVERNANCE.roles())
                        .requestMatchers("/api/governance/**", "/api/policies/**").hasAnyRole(PlatformPermission.GOVERNANCE.roles())
                        .requestMatchers("/api/control/**", "/api/agents/*/substrate/instances", "/api/agents/*/substrate/instances/**")
                            .hasAnyRole(PlatformPermission.OPERATE_INSTANCES.roles())
                        .requestMatchers(HttpMethod.POST, "/api/ax/tasks/*/suspend", "/api/ax/tasks/*/resume")
                            .hasAnyRole(PlatformPermission.OPERATE_INSTANCES.roles())
                        .requestMatchers("/api/ax/**").hasAnyRole(PlatformPermission.RUN_WORKFLOWS.roles())
                        .requestMatchers("/api/workflows/**", "/api/orchestration/**", "/api/tasks/**")
                            .hasAnyRole(PlatformPermission.RUN_WORKFLOWS.roles())
                        .requestMatchers("/api/management/connectors/**")
                            .hasAnyRole(PlatformPermission.DEPLOY_AGENTS.roles())
                        .requestMatchers(HttpMethod.GET, "/api/budgets", "/api/budgets/**")
                            .hasAnyRole(PlatformPermission.VIEW_CONSOLE.roles())
                        .requestMatchers(HttpMethod.PUT, "/api/budgets/accounts")
                            .hasAnyRole(PlatformPermission.MANAGE_BUDGETS.roles())
                        .requestMatchers(HttpMethod.POST, "/api/agentops/**")
                            .hasAnyRole(PlatformPermission.DEPLOY_AGENTS.roles())
                        .requestMatchers("/api/agents/**", "/api/registry/**", "/api/discovery/**")
                            .hasAnyRole(PlatformPermission.DEPLOY_AGENTS.roles())
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
        // With both set, keys come from jwk-set-uri and iss must still equal issuer-uri. This covers an identity
        // provider whose public issuer URL (what browsers and tokens use) is not reachable from the backend.
        JwtDecoder decoder = !properties.getJwkSetUri().isBlank()
                ? NimbusJwtDecoder.withJwkSetUri(properties.getJwkSetUri()).build()
                : JwtDecoders.fromIssuerLocation(properties.getIssuerUri());
        if (decoder instanceof NimbusJwtDecoder nimbus) {
            List<OAuth2TokenValidator<Jwt>> validators = new ArrayList<>();
            if (!properties.getIssuerUri().isBlank()) {
                validators.add(JwtValidators.createDefaultWithIssuer(properties.getIssuerUri()));
            } else {
                validators.add(JwtValidators.createDefault());
            }
            // Keycloak 25+ emits sub through the basic client scope. A role-only token
            // must never collapse different authenticated users into the console identity.
            validators.add(new JwtClaimValidator<String>("sub", subject -> subject != null && !subject.isBlank()));
            if (!properties.getAudience().isBlank()) {
                validators.add(new JwtClaimValidator<Collection<String>>("aud", audiences ->
                        audiences != null && audiences.contains(properties.getAudience())));
            }
            validators.add(projectIsolation);
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
