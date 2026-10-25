package com.eap.platform.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;

@Component
@ConfigurationProperties(prefix = "eap.security")
public class SecurityProperties {
    public enum Mode {
        DEVELOPMENT,
        JWT
    }

    private Mode mode = Mode.DEVELOPMENT;
    private String issuerUri = "";
    private String jwkSetUri = "";
    private String audience = "";
    private String rolesClaim = "roles";
    private String projectKey = "";
    private boolean initializeProjectBinding;

    public String getProjectKey() { return projectKey == null ? "" : projectKey; }
    public void setProjectKey(String value) { projectKey = value; }
    public boolean isInitializeProjectBinding() { return initializeProjectBinding; }
    public void setInitializeProjectBinding(boolean value) { initializeProjectBinding = value; }
    // Public client the console signs in with; published by /api/platform/status so the console always uses
    // the same identity provider (issuer-uri) whose tokens this backend accepts.
    private String oidcClientId = "";
    private String oidcScopes = "openid profile";
    private String allowedOrigins = "http://localhost:5173,http://127.0.0.1:5173";

    public Mode getMode() {
        return mode;
    }

    public void setMode(Mode mode) {
        this.mode = mode == null ? Mode.DEVELOPMENT : mode;
    }

    public String getIssuerUri() {
        return issuerUri == null ? "" : issuerUri.trim();
    }

    public void setIssuerUri(String issuerUri) {
        this.issuerUri = issuerUri;
    }

    public String getJwkSetUri() {
        return jwkSetUri == null ? "" : jwkSetUri.trim();
    }

    public void setJwkSetUri(String jwkSetUri) {
        this.jwkSetUri = jwkSetUri;
    }

    public String getAudience() {
        return audience == null ? "" : audience.trim();
    }

    public void setAudience(String audience) {
        this.audience = audience;
    }

    public String getRolesClaim() {
        return rolesClaim == null || rolesClaim.isBlank() ? "roles" : rolesClaim.trim();
    }

    public void setRolesClaim(String rolesClaim) {
        this.rolesClaim = rolesClaim;
    }

    public String getOidcClientId() {
        return oidcClientId == null ? "" : oidcClientId.trim();
    }

    public void setOidcClientId(String oidcClientId) {
        this.oidcClientId = oidcClientId;
    }

    public String getOidcScopes() {
        return oidcScopes == null || oidcScopes.isBlank() ? "openid profile" : oidcScopes.trim();
    }

    public void setOidcScopes(String oidcScopes) {
        this.oidcScopes = oidcScopes;
    }

    public String getAllowedOrigins() {
        return allowedOrigins == null ? "" : allowedOrigins;
    }

    public void setAllowedOrigins(String allowedOrigins) {
        this.allowedOrigins = allowedOrigins;
    }

    public List<String> allowedOriginList() {
        return Arrays.stream(getAllowedOrigins().split(","))
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .toList();
    }

    public boolean isDevelopmentMode() {
        return mode == Mode.DEVELOPMENT;
    }

    public boolean isJwtMode() {
        return mode == Mode.JWT;
    }
}
