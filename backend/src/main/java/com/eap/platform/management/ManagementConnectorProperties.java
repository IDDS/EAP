package com.eap.platform.management;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "eap.management.connectors")
public class ManagementConnectorProperties {
    private boolean schedulerEnabled = true;
    private boolean allowPrivateNetworks = false;
    private int connectTimeoutMs = 3000;
    private int readTimeoutMs = 15000;
    private int maxResponseBytes = 4 * 1024 * 1024;
    // Server-managed references; never populated by the connector CRUD API.
    private java.util.Map<String, CredentialBinding> credentials = new java.util.LinkedHashMap<>();

    public java.util.Map<String, CredentialBinding> getCredentials() { return credentials; }
    public void setCredentials(java.util.Map<String, CredentialBinding> value) { credentials = value == null ? java.util.Map.of() : value; }
    public static class CredentialBinding {
        private String envKey;
        private String origin;
        private String authType = "BEARER_ENV";
        private String header = "Authorization";
        private String prefix = "Bearer ";
        private java.util.Set<String> connectorKeys = java.util.Set.of();
        public String getEnvKey() { return envKey; }
        public void setEnvKey(String value) { envKey = value; }
        public String getOrigin() { return origin; }
        public void setOrigin(String value) { origin = value; }
        public String getAuthType() { return authType; }
        public void setAuthType(String value) { authType = value; }
        public String getHeader() { return header; }
        public void setHeader(String value) { header = value; }
        public String getPrefix() { return prefix; }
        public void setPrefix(String value) { prefix = value; }
        public java.util.Set<String> getConnectorKeys() { return connectorKeys; }
        public void setConnectorKeys(java.util.Set<String> value) { connectorKeys = value == null ? java.util.Set.of() : value; }
    }

    public boolean isSchedulerEnabled() { return schedulerEnabled; }
    public void setSchedulerEnabled(boolean schedulerEnabled) { this.schedulerEnabled = schedulerEnabled; }
    public boolean isAllowPrivateNetworks() { return allowPrivateNetworks; }
    public void setAllowPrivateNetworks(boolean allowPrivateNetworks) { this.allowPrivateNetworks = allowPrivateNetworks; }
    public int getConnectTimeoutMs() { return connectTimeoutMs; }
    public void setConnectTimeoutMs(int connectTimeoutMs) { this.connectTimeoutMs = connectTimeoutMs; }
    public int getReadTimeoutMs() { return readTimeoutMs; }
    public void setReadTimeoutMs(int readTimeoutMs) { this.readTimeoutMs = readTimeoutMs; }
    public int getMaxResponseBytes() { return maxResponseBytes; }
    public void setMaxResponseBytes(int maxResponseBytes) { this.maxResponseBytes = maxResponseBytes; }
}
