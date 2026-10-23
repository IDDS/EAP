package com.eap.platform.dashboard;

public record DashboardSummary(
        long agentCount,
        long activeAgentCount,
        long runningTaskCount,
        long riskEventCount,
        double successRate,
        long averageLatencyMs
) {
}

