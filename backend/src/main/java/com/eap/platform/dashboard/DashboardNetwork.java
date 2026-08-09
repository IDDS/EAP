package com.eap.platform.dashboard;

import java.util.List;

public record DashboardNetwork(
        List<NetworkNode> nodes,
        List<NetworkEdge> edges,
        NetworkStats stats
) {
    public record NetworkNode(
            String id,
            String label,
            String nodeType,
            String plane,
            String status,
            String riskLevel,
            String metric,
            int x,
            int y
    ) {
    }

    public record NetworkEdge(
            String id,
            String source,
            String target,
            String label,
            String plane,
            String relationType,
            String status
    ) {
    }

    public record NetworkStats(
            long controlNodeCount,
            long agentNodeCount,
            long controlEdgeCount,
            long dataEdgeCount
    ) {
    }
}
