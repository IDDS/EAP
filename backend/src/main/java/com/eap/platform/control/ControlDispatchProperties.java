package com.eap.platform.control;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "eap.control.dispatch")
public class ControlDispatchProperties {
    private boolean enabled = false;
    private String path = "/eap/control";
    private long connectTimeoutMs = 3000;
    private long readTimeoutMs = 10000;
    private long maxResponseBytes = 65536;
    private int maxAttempts = 3;
    private boolean allowPrivateNetworks = false;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getPath() { return path == null || path.isBlank() ? "/eap/control" : path; }
    public void setPath(String path) { this.path = path; }
    public long getConnectTimeoutMs() { return Math.max(100, connectTimeoutMs); }
    public void setConnectTimeoutMs(long connectTimeoutMs) { this.connectTimeoutMs = connectTimeoutMs; }
    public long getReadTimeoutMs() { return Math.max(100, readTimeoutMs); }
    public void setReadTimeoutMs(long readTimeoutMs) { this.readTimeoutMs = readTimeoutMs; }
    public long getMaxResponseBytes() { return Math.max(1024, maxResponseBytes); }
    public void setMaxResponseBytes(long maxResponseBytes) { this.maxResponseBytes = maxResponseBytes; }
    public boolean isAllowPrivateNetworks() { return allowPrivateNetworks; }
    public void setAllowPrivateNetworks(boolean allowPrivateNetworks) { this.allowPrivateNetworks = allowPrivateNetworks; }
    public int getMaxAttempts() { return Math.max(1, Math.min(maxAttempts, 10)); }
    public void setMaxAttempts(int maxAttempts) { this.maxAttempts = maxAttempts; }
}
