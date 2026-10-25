package com.eap.platform.registry;

import org.springframework.context.annotation.*;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class RuntimeAccessSecurity {
    /** Runtime bearer credentials are not human OIDC tokens; validation is local to the scoped machine routes. */
    @Bean @Order(1)
    SecurityFilterChain runtimeAccessChain(HttpSecurity http)throws Exception{
        return http.securityMatcher("/api/runtime-access/v1/**").csrf(c->c.disable())
                .sessionManagement(s->s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(a->a.requestMatchers(HttpMethod.POST,"/api/runtime-access/v1/chat/completions","/api/runtime-access/v1/traces","/api/runtime-access/v1/execution-events","/api/runtime-access/v1/execution-delivery","/api/runtime-access/v1/execution-links","/api/runtime-access/v1/tools/check","/api/runtime-access/v1/tools/claim","/api/runtime-access/v1/tools/receipt","/api/runtime-access/v1/tools/scoped/delegate","/api/runtime-access/v1/tools/scoped/execute").permitAll()
                .requestMatchers(HttpMethod.GET,"/api/runtime-access/v1/tools/scoped/operations").permitAll().anyRequest().denyAll()).build();
    }
}
