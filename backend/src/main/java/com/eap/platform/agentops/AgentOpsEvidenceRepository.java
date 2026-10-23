package com.eap.platform.agentops;

import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Repository;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/** Reads only per-agent presence and latest timestamps, never event bodies. */
@Repository
public class AgentOpsEvidenceRepository {
    private final EntityManager entityManager;

    public AgentOpsEvidenceRepository(EntityManager entityManager) { this.entityManager = entityManager; }

    public Map<Long, Evidence> summarize(Instant windowStart, Long agentId) {
        Map<Long, Evidence> evidence = new HashMap<>();
        aggregate(evidence, "ModelInvocationEvent", "observedAt", "MODEL_TELEMETRY", windowStart, agentId);
        aggregate(evidence, "TraceSpanObservation", "observedAt", "TRACE", windowStart, agentId);
        aggregate(evidence, "ToolInvocationEvent", "observedAt", "TOOL_MCP", windowStart, agentId);
        aggregate(evidence, "ContentObservation", "observedAt", "CONTENT_METADATA", windowStart, agentId);
        aggregate(evidence, "GovernanceDataEvent", "observedAt", "GOVERNANCE", windowStart, agentId);
        aggregate(evidence, "KnowledgeInteraction", "createdAt", "KNOWLEDGE", windowStart, agentId);
        aggregate(evidence, "AgentTask", "updatedAt", "TASKS", windowStart, agentId);
        var riskQuery = entityManager.createQuery("select e.agentId from GovernanceDataEvent e "
                + "where e.agentId is not null and e.observedAt >= :since "
                + "and (e.companySecret = true or e.action = com.eap.platform.governance.GovernanceDataAction.BLOCK) "
                + (agentId == null ? "" : "and e.agentId = :agentId ") + "group by e.agentId", Long.class);
        riskQuery.setParameter("since", windowStart);
        if (agentId != null) riskQuery.setParameter("agentId", agentId);
        riskQuery.getResultList().forEach(id -> evidence.computeIfAbsent(id, ignored -> new Evidence()).highGovernanceRisk = true);
        return evidence;
    }

    private void aggregate(Map<Long, Evidence> result, String entity, String timeField, String signal,
                           Instant windowStart, Long agentId) {
        // Entity and field identifiers are fixed literals supplied only above.
        var query = entityManager.createQuery("select e.agentId, max(e." + timeField + ") from " + entity + " e "
                + "where e.agentId is not null and e." + timeField + " >= :since "
                + (agentId == null ? "" : "and e.agentId = :agentId ") + "group by e.agentId", Object[].class);
        query.setParameter("since", windowStart);
        if (agentId != null) query.setParameter("agentId", agentId);
        for (Object[] row : query.getResultList()) {
            result.computeIfAbsent((Long) row[0], ignored -> new Evidence()).latestBySignal.put(signal, (Instant) row[1]);
        }
    }

    public static final class Evidence {
        private final Map<String, Instant> latestBySignal = new LinkedHashMap<>();
        private boolean highGovernanceRisk;
        public Map<String, Instant> latestBySignal() { return latestBySignal; }
        public boolean highGovernanceRisk() { return highGovernanceRisk; }
    }
}
