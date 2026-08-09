package com.eap.platform.discovery;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
@ConfigurationProperties(prefix = "eap.discovery.protocol")
public class ProtocolDiscoveryProperties {
    private boolean enabled = true;
    private List<String> targets = new ArrayList<>();
    private long initialDelayMs = 60_000;
    private long intervalMs = 300_000;
    private long connectTimeoutMs = 3_000;
    private long readTimeoutMs = 10_000;
    private int maxResponseBytes = 262_144;
    private int maxServersPerRegistry = 100;
    private boolean allowPrivateNetworks = false;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public List<String> getTargets() { return targets; }
    public void setTargets(List<String> targets) { this.targets = targets == null ? new ArrayList<>() : targets; }
    public long getInitialDelayMs() { return initialDelayMs; }
    public void setInitialDelayMs(long initialDelayMs) { this.initialDelayMs = initialDelayMs; }
    public long getIntervalMs() { return intervalMs; }
    public void setIntervalMs(long intervalMs) { this.intervalMs = intervalMs; }
    public long getConnectTimeoutMs() { return connectTimeoutMs; }
    public void setConnectTimeoutMs(long connectTimeoutMs) { this.connectTimeoutMs = connectTimeoutMs; }
    public long getReadTimeoutMs() { return readTimeoutMs; }
    public void setReadTimeoutMs(long readTimeoutMs) { this.readTimeoutMs = readTimeoutMs; }
    public int getMaxResponseBytes() { return maxResponseBytes; }
    public void setMaxResponseBytes(int maxResponseBytes) { this.maxResponseBytes = maxResponseBytes; }
    public int getMaxServersPerRegistry() { return maxServersPerRegistry; }
    public void setMaxServersPerRegistry(int maxServersPerRegistry) { this.maxServersPerRegistry = maxServersPerRegistry; }
    public boolean isAllowPrivateNetworks() { return allowPrivateNetworks; }
    public void setAllowPrivateNetworks(boolean allowPrivateNetworks) { this.allowPrivateNetworks = allowPrivateNetworks; }
}
