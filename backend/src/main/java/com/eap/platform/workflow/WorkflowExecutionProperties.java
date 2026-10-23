package com.eap.platform.workflow;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

@Component
@ConfigurationProperties(prefix = "eap.workflow.execution")
public class WorkflowExecutionProperties {
    private boolean enabled;
    private String allowedHosts = "";
    private String token = "";
    private boolean allowInsecureHttp;
    private boolean allowPrivateNetworks;
    private int connectTimeoutMs = 3000;
    private int readTimeoutMs = 10000;
    private int maxResponseBytes = 65536;
    private int timeoutSeconds = 900;
    private int agingSeconds = 60;
    public int getAgingSeconds() { return Math.max(1, Math.min(3600, agingSeconds)); }
    public void setAgingSeconds(int value) { agingSeconds = value; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean value) { enabled = value; }
    public String getAllowedHosts() { return allowedHosts; }
    public void setAllowedHosts(String value) { allowedHosts = value == null ? "" : value; }
    public String getToken() { return token; }
    public void setToken(String value) { token = value == null ? "" : value; }
    public boolean isAllowInsecureHttp() { return allowInsecureHttp; }
    public void setAllowInsecureHttp(boolean value) { allowInsecureHttp = value; }
    public boolean isAllowPrivateNetworks() { return allowPrivateNetworks; }
    public void setAllowPrivateNetworks(boolean value) { allowPrivateNetworks = value; }
    public int getConnectTimeoutMs() { return Math.max(100, connectTimeoutMs); }
    public void setConnectTimeoutMs(int value) { connectTimeoutMs = value; }
    public int getReadTimeoutMs() { return Math.max(100, readTimeoutMs); }
    public void setReadTimeoutMs(int value) { readTimeoutMs = value; }
    public int getMaxResponseBytes() { return Math.max(1024, Math.min(1048576, maxResponseBytes)); }
    public void setMaxResponseBytes(int value) { maxResponseBytes = value; }
    public int getTimeoutSeconds() { return Math.max(1, timeoutSeconds); }
    public void setTimeoutSeconds(int value) { timeoutSeconds = value; }
    public List<String> hosts() { return Arrays.stream(allowedHosts.split(",")).map(String::trim)
            .filter(value -> !value.isBlank()).map(value -> value.toLowerCase(Locale.ROOT)).toList(); }
    public boolean ready() { return enabled && !token.isBlank() && !hosts().isEmpty(); }
}
