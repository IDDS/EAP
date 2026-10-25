package com.eap.platform.observability;

import com.eap.platform.deployment.RuntimePlacementService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

/** Separate short transactions; no control-plane/network call is made while holding a DB lock. */
@Service
public class RuntimePlacementEvidenceStore {
    private final JdbcTemplate db;
    private final ObjectMapper mapper;

    public RuntimePlacementEvidenceStore(JdbcTemplate db, ObjectMapper mapper) {
        this.db = db;
        this.mapper = mapper;
    }

    public enum Phase { DISPATCH_REQUESTED, RECEIPT_RETURNED }
    public record Request(String operationKey, String rawTrace, Long agentId, Long runtimeId,
                          String instanceKey, String endpoint, String actorUid, String template,
                          Phase phase, Instant requestedAt) {}
    public record Claim(long operationId, Request request) {}
    public record Evidence(Phase phase, Instant requestedAt, String captureState,
                           RuntimePlacementService.Snapshot snapshot) {}

    @Transactional(propagation = Propagation.REQUIRES_NEW, timeout = 3)
    public Claim claim(Request request) {
        var rows = db.queryForList("select o.id from observation_operations o where o.operation_key=? "
                + "and o.entry_kind='RUNTIME_TASK' and o.registration_state='CONFIRMED' and o.raw_trace_id=? "
                + "and o.agent_id=? and o.runtime_id=? and o.instance_key=? and o.endpoint=? and o.actor_uid=? "
                + "and o.actor_template=? and exists (select 1 from observation_operation_events e "
                + "where e.operation_id=o.id and e.event_kind='DISPATCH_COMMITTED') for update",
                request.operationKey(), request.rawTrace(), request.agentId(), request.runtimeId(), request.instanceKey(),
                request.endpoint(), request.actorUid(), request.template());
        if (rows.isEmpty()) return null;
        long id = ((Number) rows.getFirst().get("id")).longValue();
        if (db.queryForObject("select count(*) from observation_runtime_placements where operation_id=? and phase=?",
                Long.class, id, request.phase().name()) != 0) return null;
        db.update("insert into observation_runtime_placements(operation_id,phase,requested_at) values(?,?,?)",
                id, request.phase().name(), Timestamp.from(request.requestedAt()));
        return new Claim(id, request);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, timeout = 3)
    public boolean complete(Claim claim, RuntimePlacementService.Snapshot snapshot) {
        String json;
        try { json = mapper.writeValueAsString(snapshot); }
        catch (JsonProcessingException e) { throw new IllegalStateException("Placement encoding failed"); }
        return db.update("update observation_runtime_placements set snapshot_json=?,completed_at=CURRENT_TIMESTAMP(6) "
                + "where operation_id=? and phase=? and snapshot_json is null",
                json, claim.operationId(), claim.request().phase().name()) == 1;
    }

    /** Caller must first authorize access to the parent operation. Never probes the current runtime. */
    @Transactional(readOnly = true)
    public List<Evidence> recorded(long operationId) {
        return db.query("select phase,requested_at,snapshot_json from observation_runtime_placements "
                + "where operation_id=? order by phase", (r, n) -> {
            String json = r.getString("snapshot_json");
            RuntimePlacementService.Snapshot snapshot = null;
            if (json != null) {
                try { snapshot = mapper.readValue(json, RuntimePlacementService.Snapshot.class); }
                catch (JsonProcessingException e) { throw new IllegalStateException("Placement evidence unreadable"); }
            }
            return new Evidence(Phase.valueOf(r.getString("phase")), r.getTimestamp("requested_at").toInstant(),
                    snapshot == null ? "INCOMPLETE" : "RECORDED", snapshot);
        }, operationId);
    }
}
