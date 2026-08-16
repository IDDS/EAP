package com.eap.platform.gateway;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

@Component
@ConfigurationProperties(prefix = "eap.gateway")
public class GatewayProperties {
    private boolean enabled = false;
    private String upstreamBaseUrl = "";
    private String upstreamApiKey = "";
    private String ingestionToken = "";
    private boolean allowUnauthenticated = false;
    private boolean autoAttributionEnabled = true;
    private String provider = "openai-compatible";
    private String usagePoolKey = "";
    private String classifier = "eap-heuristic-v1";
    private long connectTimeoutMs = 3000;
    private long readTimeoutMs = 120000;
    private long maxPayloadBytes = 1048576;
    private long maxResponseBytes = 4194304;
    private BigDecimal inputCostPer1kTokens = BigDecimal.ZERO;
    private BigDecimal outputCostPer1kTokens = BigDecimal.ZERO;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getUpstreamBaseUrl() { return upstreamBaseUrl == null ? "" : upstreamBaseUrl; }
    public void setUpstreamBaseUrl(String upstreamBaseUrl) { this.upstreamBaseUrl = upstreamBaseUrl; }
    public String getUpstreamApiKey() { return upstreamApiKey == null ? "" : upstreamApiKey; }
    public void setUpstreamApiKey(String upstreamApiKey) { this.upstreamApiKey = upstreamApiKey; }
    public String getIngestionToken() { return ingestionToken == null ? "" : ingestionToken; }
    public void setIngestionToken(String ingestionToken) { this.ingestionToken = ingestionToken; }
    public boolean isAllowUnauthenticated() { return allowUnauthenticated; }
    public void setAllowUnauthenticated(boolean allowUnauthenticated) { this.allowUnauthenticated = allowUnauthenticated; }
    public boolean isAutoAttributionEnabled() { return autoAttributionEnabled; }
    public void setAutoAttributionEnabled(boolean autoAttributionEnabled) { this.autoAttributionEnabled = autoAttributionEnabled; }
    public String getProvider() { return provider == null ? "openai-compatible" : provider; }
    public void setProvider(String provider) { this.provider = provider; }
    public String getUsagePoolKey() { return usagePoolKey == null ? "" : usagePoolKey; }
    public void setUsagePoolKey(String usagePoolKey) { this.usagePoolKey = usagePoolKey; }
    public String getClassifier() { return classifier == null ? "eap-heuristic-v1" : classifier; }
    public void setClassifier(String classifier) { this.classifier = classifier; }
    public long getConnectTimeoutMs() { return connectTimeoutMs; }
    public void setConnectTimeoutMs(long connectTimeoutMs) { this.connectTimeoutMs = connectTimeoutMs; }
    public long getReadTimeoutMs() { return readTimeoutMs; }
    public void setReadTimeoutMs(long readTimeoutMs) { this.readTimeoutMs = readTimeoutMs; }
    public long getMaxPayloadBytes() { return maxPayloadBytes; }
    public void setMaxPayloadBytes(long maxPayloadBytes) { this.maxPayloadBytes = maxPayloadBytes; }
    public long getMaxResponseBytes() { return maxResponseBytes; }
    public void setMaxResponseBytes(long maxResponseBytes) { this.maxResponseBytes = maxResponseBytes; }
    public BigDecimal getInputCostPer1kTokens() { return inputCostPer1kTokens == null ? BigDecimal.ZERO : inputCostPer1kTokens; }
    public void setInputCostPer1kTokens(BigDecimal inputCostPer1kTokens) { this.inputCostPer1kTokens = inputCostPer1kTokens; }
    public BigDecimal getOutputCostPer1kTokens() { return outputCostPer1kTokens == null ? BigDecimal.ZERO : outputCostPer1kTokens; }
    public void setOutputCostPer1kTokens(BigDecimal outputCostPer1kTokens) { this.outputCostPer1kTokens = outputCostPer1kTokens; }
}
