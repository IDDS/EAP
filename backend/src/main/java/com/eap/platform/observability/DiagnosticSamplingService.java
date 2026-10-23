package com.eap.platform.observability;

import com.eap.platform.agent.AgentService;
import com.eap.platform.common.BusinessException;
import com.eap.platform.registry.AgentRuntimeInstance;
import com.eap.platform.registry.AgentRuntimeInstanceRepository;
import com.eap.platform.registry.RuntimeConnectionService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigInteger;
import java.time.Instant;
import java.util.*;

/** Only agent.diagnostic is optional. Execution, gateway, identity, budget and tool evidence are untouched. */
@Service
public class DiagnosticSamplingService {
    public record TrialDecision(long trialId, String traceId, Long policyVersion, Boolean selected,
            String identityEvidence, String reasonCode, String executionStatus, String receiptStatus,
            long retained, long filtered, long duplicateAttempts, long limitDropped, Instant decidedAt) {}
    public record Evidence(long experimentId, String entryPoint, int registeredTrials, List<TrialDecision> trials) {}
    public record Filtered(JsonNode payload, List<String> warnings) {}
    private final JdbcTemplate db;
    private final DiagnosticPolicyService policies;
    private final AgentRuntimeInstanceRepository runtimes;
    private final AgentService agents;
    private final ObjectMapper json;

    public DiagnosticSamplingService(JdbcTemplate db, DiagnosticPolicyService policies,
            AgentRuntimeInstanceRepository runtimes, AgentService agents, ObjectMapper json) {
        this.db = db; this.policies = policies; this.runtimes = runtimes; this.agents = agents; this.json = json;
    }

    /** Called in the original experiment registration transaction, after trial IDs and traces have been persisted. */
    @Transactional
    public void register(long experimentId, AgentRuntimeInstance runtime, String actorUid, String template) {
        var policy = policies.freezeCurrent();
        boolean confirmed = runtime.supportsMachineIdentity() && Objects.equals(runtime.getBoundActorUid(), actorUid)
                && Objects.equals(runtime.getBoundActorTemplate(), template);
        for (var trial : db.queryForList("select id,trace_id from agent_experiment_trials where experiment_id=? order by id", experimentId)) {
            String trace = (String) trial.get("trace_id");
            Boolean selected = confirmed ? chooses(policy, runtime, trace) : null;
            db.update("insert into experiment_diagnostic_decisions(trial_id,policy_version,runtime_id,agent_id,instance_key,endpoint,actor_uid,actor_template,trace_id,identity_confirmed,selected,reason_code,created_at) values(?,?,?,?,?,?,?,?,?,?,?,?,CURRENT_TIMESTAMP)",
                    trial.get("id"), policy.version(), runtime.getId(), runtime.getAgentId(), runtime.getInstanceKey(),
                    runtime.getEndpoint(), actorUid, template, trace, confirmed, selected,
                    !confirmed ? "IDENTITY_UNCONFIRMED" : selected ? "POLICY_SELECTED" : "POLICY_NOT_SELECTED");
        }
    }

    private boolean chooses(DiagnosticPolicyService.Policy policy, AgentRuntimeInstance runtime, String trace) {
        String input = policy.algorithm() + "|" + policy.version() + "|" + runtime.getAgentId() + "|"
                + runtime.getId() + "|" + runtime.getInstanceKey() + "|" + trace.toLowerCase(Locale.ROOT);
        int bucket = new BigInteger(DiagnosticPolicyService.hash(input).substring(0, 16), 16)
                .mod(BigInteger.valueOf(10000)).intValue();
        return bucket < policy.sampleBasisPoints();
    }

    /** Input has already passed runtime authentication, identity validation, size limits and trace namespacing. */
    @Transactional
    public Filtered filter(JsonNode scoped, RuntimeConnectionService.Access access) {
        ObjectNode result = scoped.deepCopy();
        var resources = result.withArray("resourceSpans");
        var diagnostics = json.createArrayNode();
        var warnings = new LinkedHashSet<String>();
        // Lock registered traces in stable order so mixed-trace uploads cannot deadlock each other.
        var choices = new TreeMap<String, Map<String, Object>>();
        for (var resource : resources) for (var scope : resource.path("scopeSpans")) for (var span : scope.path("spans"))
            if (optional(span)) choices.put(span.path("traceId").asText(), Map.of());
        for (var trace : choices.keySet()) {
            var rows = db.queryForList("select * from experiment_diagnostic_decisions where trace_id=? for update", trace);
            if (!rows.isEmpty()) choices.put(trace, rows.getFirst());
        }
        for (var resource : resources) for (var scope : resource.path("scopeSpans")) {
            var kept = json.createArrayNode();
            for (var span : scope.path("spans")) {
                if (!optional(span)) { kept.add(span); continue; }
                var decision = choices.get(span.path("traceId").asText());
                if (decision.isEmpty() || !matches(decision, access)) { warnings.add("DIAGNOSTIC_IDENTITY_UNCONFIRMED"); continue; }
                long trial = ((Number) decision.get("trial_id")).longValue();
                String spanId = span.path("spanId").asText();
                if (!db.queryForList("select span_id from experiment_diagnostic_receipts where trial_id=? and span_id=?", trial, spanId).isEmpty()) {
                    db.update("update experiment_diagnostic_receipts set attempts=attempts+1 where trial_id=? and span_id=?", trial, spanId);
                    warnings.add("DIAGNOSTIC_DUPLICATE"); continue;
                }
                if (db.queryForObject("select count(*) from experiment_diagnostic_receipts where trial_id=?", Long.class, trial) >= 16) {
                    db.update("update experiment_diagnostic_decisions set limit_dropped=limit_dropped+1 where trial_id=?", trial);
                    warnings.add("DIAGNOSTIC_LIMIT_EXCEEDED"); continue;
                }
                String status = db.queryForObject("select status from agent_experiment_trials where id=?", String.class, trial);
                boolean unconfirmed = Set.of("FAILED", "UNKNOWN").contains(status);
                boolean retain = Boolean.TRUE.equals(decision.get("selected")) || unconfirmed;
                String reason = unconfirmed ? "SERVER_EXECUTION_UNCONFIRMED" : (String) decision.get("reason_code");
                db.update("insert into experiment_diagnostic_receipts(trial_id,span_id,disposition,reason_code,created_at) values(?,?,?,?,CURRENT_TIMESTAMP)",
                        trial, spanId, retain ? "RETAINED" : "FILTERED", reason);
                if (retain) diagnostics.add(safeSpan(span, reason)); else warnings.add("DIAGNOSTIC_POLICY_NOT_SELECTED");
            }
            ((ObjectNode) scope).set("spans", kept);
        }
        for (int r = resources.size() - 1; r >= 0; r--) {
            var scopes = (com.fasterxml.jackson.databind.node.ArrayNode) resources.get(r).path("scopeSpans");
            for (int s = scopes.size() - 1; s >= 0; s--) if (scopes.get(s).path("spans").isEmpty()) scopes.remove(s);
            if (scopes.isEmpty()) resources.remove(r);
        }
        if (!diagnostics.isEmpty()) {
            var resource = json.createObjectNode(); // No resource content or caller risk/status survives this optional path.
            resource.putObject("resource").putArray("attributes");
            var scope = resource.putArray("scopeSpans").addObject();
            scope.putObject("scope").put("name", "eap.diagnostics.v1"); scope.set("spans", diagnostics);
            resources.add(resource);
        }
        return new Filtered(result, List.copyOf(warnings));
    }

    private boolean optional(JsonNode span) { return "agent.diagnostic".equals(span.path("name").asText()); }

    private JsonNode safeSpan(JsonNode original, String reason) {
        var safe = json.createObjectNode();
        for (String name : List.of("traceId", "spanId", "parentSpanId")) if (original.has(name)) safe.set(name, original.get(name));
        // Preserve valid producer boundaries; no caller text or arbitrary values enter the diagnostic projection.
        try {
            long start = Long.parseLong(original.path("startTimeUnixNano").asText());
            long end = Long.parseLong(original.path("endTimeUnixNano").asText());
            if (start > 0 && end >= start) {
                safe.put("startTimeUnixNano", Long.toString(start)); safe.put("endTimeUnixNano", Long.toString(end));
            }
        } catch (NumberFormatException ignored) { }
        safe.put("name", "agent.diagnostic");
        // Caller errors, events, links, content, tenant, risk, approval and arbitrary attributes are never stored.
        safe.putObject("attributes").put("eap.diagnostic.reason", reason);
        return safe;
    }

    private boolean matches(Map<String, Object> d, RuntimeConnectionService.Access access) {
        if (!Boolean.TRUE.equals(d.get("identity_confirmed")) || !Objects.equals(d.get("agent_id"), access.agentId())
                || !Objects.equals(d.get("instance_key"), access.instanceKey())
                || !Objects.equals("runtime-" + d.get("runtime_id"), access.endpointKey())) return false;
        return runtimes.findByInstanceKey(access.instanceKey()).filter(r -> frozenMatches(d, r)).isPresent();
    }

    private boolean frozenMatches(Map<String, Object> d, AgentRuntimeInstance r) {
        return r.supportsMachineIdentity() && Objects.equals(r.getId(), d.get("runtime_id"))
                && Objects.equals(r.getAgentId(), d.get("agent_id")) && Objects.equals(r.getEndpoint(), d.get("endpoint"))
                && Objects.equals(r.getBoundActorUid(), d.get("actor_uid")) && Objects.equals(r.getBoundActorTemplate(), d.get("actor_template"));
    }

    @Transactional(readOnly = true)
    public Evidence evidence(long experimentId) {
        var roots = db.queryForList("select agent_id from agent_experiments where id=?", experimentId);
        if (roots.isEmpty()) throw new BusinessException("实验不存在");
        agents.get(((Number) roots.getFirst().get("agent_id")).longValue());
        var decisions = new ArrayList<TrialDecision>();
        for (var t : db.queryForList("select id,trace_id,status from agent_experiment_trials where experiment_id=? order by id", experimentId)) {
            long id = ((Number) t.get("id")).longValue();
            var rows = db.queryForList("select * from experiment_diagnostic_decisions where trial_id=?", id);
            if (rows.isEmpty()) {
                decisions.add(new TrialDecision(id, (String) t.get("trace_id"), null, null, "UNREGISTERED",
                        "POLICY_UNCONFIRMED", (String) t.get("status"), "DENOMINATOR_UNCONFIRMED", 0, 0, 0, 0, null)); continue;
            }
            var d = rows.getFirst();
            boolean confirmed = Boolean.TRUE.equals(d.get("identity_confirmed")) && runtimes.findByInstanceKey((String) d.get("instance_key"))
                    .filter(r -> frozenMatches(d, r)).isPresent();
            long retained = 0, filtered = 0, duplicates = 0;
            for (var receipt : db.queryForList("select disposition,attempts from experiment_diagnostic_receipts where trial_id=?", id)) {
                if ("RETAINED".equals(receipt.get("disposition"))) retained++; else filtered++;
                duplicates += ((Number) receipt.get("attempts")).longValue() - 1;
            }
            long dropped = ((Number) d.get("limit_dropped")).longValue();
            String receiptState = dropped > 0 ? "LIMIT_EXCEEDED" : retained > 0 && filtered > 0 ? "MIXED"
                    : retained > 0 ? "RETAINED" : filtered > 0 ? "FILTERED" : "NOT_RECEIVED";
            decisions.add(new TrialDecision(id, (String) d.get("trace_id"), ((Number) d.get("policy_version")).longValue(),
                    (Boolean) d.get("selected"), confirmed ? "FROZEN_REGISTRATION_MATCH" : "UNCONFIRMED",
                    (String) d.get("reason_code"), (String) t.get("status"), receiptState, retained, filtered, duplicates,
                    dropped, ((java.sql.Timestamp) d.get("created_at")).toInstant()));
        }
        return new Evidence(experimentId, "EXPERIMENT", decisions.size(), List.copyOf(decisions));
    }
}
