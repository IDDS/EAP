package com.eap.platform.governance;

public enum GovernanceGatewayDirective {
    PROCEED,
    PROCEED_WITH_WARNING,
    REDACT_AND_RECHECK,
    REQUEST_APPROVAL,
    BLOCK
}
