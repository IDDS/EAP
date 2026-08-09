package com.eap.platform.telemetry;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "eap.telemetry")
public class TelemetryProperties {
    private String ingestionToken = "";
    private boolean requireGovernancePreflight = true;
    private long governanceDecisionTtlSeconds = 300;

    public String getIngestionToken() {
        return ingestionToken;
    }

    public void setIngestionToken(String ingestionToken) {
        this.ingestionToken = ingestionToken == null ? "" : ingestionToken;
    }

    public boolean isRequireGovernancePreflight() {
        return requireGovernancePreflight;
    }

    public void setRequireGovernancePreflight(boolean requireGovernancePreflight) {
        this.requireGovernancePreflight = requireGovernancePreflight;
    }

    public long getGovernanceDecisionTtlSeconds() {
        return governanceDecisionTtlSeconds;
    }

    public void setGovernanceDecisionTtlSeconds(long governanceDecisionTtlSeconds) {
        this.governanceDecisionTtlSeconds = governanceDecisionTtlSeconds;
    }
}
