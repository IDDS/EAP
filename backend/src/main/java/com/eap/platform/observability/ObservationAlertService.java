package com.eap.platform.observability;

import com.eap.platform.common.BusinessException;
import com.eap.platform.common.ConflictException;
import jakarta.validation.constraints.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.util.*;

/** Metadata-only governance. An acknowledgement never settles an unknown operation or its budget. */
@Service
public class ObservationAlertService {
    private final JdbcTemplate db;
    private final ObservationCoverageService coverage;
    public ObservationAlertService(JdbcTemplate db, ObservationCoverageService coverage) {
        this.db = db; this.coverage = coverage;
    }
    public record Acknowledge(@Min(1) long factRevision,
                              @NotBlank @Pattern(regexp="[a-f0-9]{64}") String factDigest) {}
    public record Alert(long id, String kind, long factRevision, String factDigest, String coverage,
                        String executionStatus, String lifecycleState, long createdMs, long changedMs) {}
    public record Audit(long alertId, String event, String principalDigest, long createdMs) {}
    public record View(ObservationCoverageService.Operation operation, String observedFactDigest,
                       Long reconciledRevision, String reconciledFactDigest, boolean reconciliationCurrent,
                       List<Alert> alerts, boolean alertsTruncated, List<Audit> audit, boolean auditTruncated) {}
    private static final Set<String> QUIET = Set.of("COMPLETE", "PENDING", "NOT_EXECUTED", "NOT_EXPECTED");

    @Transactional(readOnly=true, isolation=Isolation.REPEATABLE_READ, timeout=5)
    public View inspect(long operation) { return view(coverage.fact(operation)); }

    /** Lock the original enrollment before the first consistent read; duplicate writers serialize per operation. */
    private ObservationCoverageService.Fact locked(long operation) {
        if (operation < 1 || db.queryForList("select id from observation_operations where id=? for update", operation).isEmpty())
            throw new BusinessException("观测登记不存在");
        return coverage.fact(operation);
    }
    private Set<String> kinds(ObservationCoverageService.Operation operation) {
        var result = new LinkedHashSet<String>();
        if (!QUIET.contains(operation.coverage())) result.add("EVIDENCE_GAP");
        if (Set.of("UNKNOWN", "EXECUTION_UNKNOWN").contains(operation.executionStatus())) result.add("EXECUTION_UNKNOWN");
        return result;
    }
    @Transactional(isolation=Isolation.READ_COMMITTED, timeout=5)
    public View reconcile(long operation, String principal) {
        var fact = locked(operation);
        var heads = db.queryForList("select fact_revision,fact_digest from observation_alert_heads where operation_id=?", operation);
        if (!heads.isEmpty() && fact.digest().equals(heads.getFirst().get("fact_digest"))) return view(fact);
        long revision = heads.isEmpty() ? 1 : Math.addExact(number(heads.getFirst(), "fact_revision"), 1);
        var wanted = kinds(fact.operation());
        var previous = db.queryForList("select id,alert_kind from observation_alerts where operation_id=? and lifecycle_state in ('ACTIVE','ACKNOWLEDGED') order by id", operation);
        for (var old : previous) {
            String state = wanted.contains(old.get("alert_kind")) ? "SUPERSEDED" : "RESOLVED";
            long id = number(old, "id");
            db.update("update observation_alerts set lifecycle_state=?,changed_at=CURRENT_TIMESTAMP(6) where id=?", state, id);
            audit(id, state, principal);
        }
        if (heads.isEmpty()) db.update("insert into observation_alert_heads values(?,?,?,CURRENT_TIMESTAMP(6))", operation, revision, fact.digest());
        else db.update("update observation_alert_heads set fact_revision=?,fact_digest=?,evaluated_at=CURRENT_TIMESTAMP(6) where operation_id=?", revision, fact.digest(), operation);
        var o = fact.operation();
        for (String kind : wanted) {
            db.update("insert into observation_alerts(operation_id,agent_id,entry_kind,alert_kind,fact_revision,fact_digest,coverage,execution_status,lifecycle_state,created_at,changed_at) values(?,?,?,?,?,?,?,?,'ACTIVE',CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6))",
                    operation, o.agentId(), o.entryKind(), kind, revision, fact.digest(), o.coverage(), o.executionStatus());
            long id = db.queryForObject("select id from observation_alerts where operation_id=? and alert_kind=? and fact_revision=?", Long.class, operation, kind, revision);
            audit(id, "CREATED", principal);
        }
        return view(fact);
    }
    @Transactional(isolation=Isolation.READ_COMMITTED, timeout=5)
    public View acknowledge(long operation, long alert, Acknowledge request, String principal) {
        var fact = locked(operation);
        var rows = db.queryForList("select * from observation_alerts where id=? and operation_id=?", alert, operation);
        var heads = db.queryForList("select fact_revision,fact_digest from observation_alert_heads where operation_id=?", operation);
        if (rows.isEmpty() || heads.isEmpty()) throw new BusinessException("告警不存在");
        var row = rows.getFirst(); var head = heads.getFirst();
        if (request == null || request.factRevision() != number(row, "fact_revision")
                || request.factRevision() != number(head, "fact_revision")
                || !Objects.equals(request.factDigest(), row.get("fact_digest"))
                || !Objects.equals(request.factDigest(), head.get("fact_digest"))
                || !Objects.equals(request.factDigest(), fact.digest()))
            throw new ConflictException("告警事实已变化，请重新核对并协调原登记");
        if ("ACKNOWLEDGED".equals(row.get("lifecycle_state"))) return view(fact);
        if (!"ACTIVE".equals(row.get("lifecycle_state"))) throw new ConflictException("告警已关闭");
        db.update("update observation_alerts set lifecycle_state='ACKNOWLEDGED',changed_at=CURRENT_TIMESTAMP(6) where id=?", alert);
        audit(alert, "ACKNOWLEDGED", principal);
        return view(fact);
    }
    private View view(ObservationCoverageService.Fact fact) {
        long operation = fact.operation().id();
        var heads = db.queryForList("select fact_revision,fact_digest from observation_alert_heads where operation_id=?", operation);
        Long revision = heads.isEmpty() ? null : number(heads.getFirst(), "fact_revision");
        String digest = heads.isEmpty() ? null : (String) heads.getFirst().get("fact_digest");
        var alerts = db.queryForList("select * from observation_alerts where operation_id=? order by id desc limit 101", operation);
        var history = db.queryForList("select e.* from observation_alert_audit e join observation_alerts a on a.id=e.alert_id where a.operation_id=? order by a.id desc,e.created_at desc,e.event_kind limit 201", operation);
        return new View(fact.operation(), fact.digest(), revision, digest, fact.digest().equals(digest),
                alerts.stream().limit(100).map(r -> new Alert(number(r,"id"), (String)r.get("alert_kind"), number(r,"fact_revision"), (String)r.get("fact_digest"),
                        (String)r.get("coverage"), (String)r.get("execution_status"), (String)r.get("lifecycle_state"), millis(r,"created_at"), millis(r,"changed_at"))).toList(), alerts.size()>100,
                history.stream().limit(200).map(r -> new Audit(number(r,"alert_id"), (String)r.get("event_kind"), (String)r.get("principal_digest"), millis(r,"created_at"))).toList(), history.size()>200);
    }
    private void audit(long id, String event, String principal) {
        if (principal == null || principal.isBlank() || principal.length() > 1024) throw new BusinessException("告警审计主体无效");
        try {
            String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(principal.getBytes(StandardCharsets.UTF_8)));
            db.update("insert into observation_alert_audit values(?,?,?,CURRENT_TIMESTAMP(6))", id, event, digest);
        } catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException("告警审计摘要不可用"); }
    }
    private static long number(Map<String,Object> row, String field) { return ((Number)row.get(field)).longValue(); }
    private static long millis(Map<String,Object> row, String field) { return ((Timestamp)row.get(field)).toInstant().toEpochMilli(); }
}
