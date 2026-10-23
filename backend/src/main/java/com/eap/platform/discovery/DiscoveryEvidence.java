package com.eap.platform.discovery;

import com.eap.platform.agent.RiskLevel;

import java.util.Map;

public record DiscoveryEvidence(
        String fingerprint,
        String name,
        String vendor,
        String product,
        String version,
        DiscoveryAssetType assetType,
        DiscoverySourceType sourceType,
        String sourceLocation,
        String hostName,
        String accountName,
        String processName,
        String executablePath,
        Boolean autoApprove,
        Boolean trustedProcess,
        int mcpServerCount,
        int confidence,
        RiskLevel riskLevel,
        String evidenceType,
        Map<String, Object> evidence
) {
}
