package com.eap.platform.governance;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "eap.governance-data")
public class GovernanceDataProperties {
    private String ingestionToken = "";
    private String approvalToken = "";
    private boolean enforcementEnabled = true;
    private boolean failClosedOnSecret = true;

    public String getIngestionToken() {
        return ingestionToken == null ? "" : ingestionToken;
    }

    public void setIngestionToken(String ingestionToken) {
        this.ingestionToken = ingestionToken;
    }

    public String getApprovalToken() {
        return approvalToken == null ? "" : approvalToken;
    }

    public void setApprovalToken(String approvalToken) {
        this.approvalToken = approvalToken;
    }

    public boolean isEnforcementEnabled() {
        return enforcementEnabled;
    }

    public void setEnforcementEnabled(boolean enforcementEnabled) {
        this.enforcementEnabled = enforcementEnabled;
    }

    public boolean isFailClosedOnSecret() {
        return failClosedOnSecret;
    }

    public void setFailClosedOnSecret(boolean failClosedOnSecret) {
        this.failClosedOnSecret = failClosedOnSecret;
    }
}
