package com.eap.platform.workflow;

import com.eap.platform.audit.AuditLog;
import com.eap.platform.audit.AuditLogRepository;
import com.eap.platform.common.BusinessException;
import com.eap.platform.common.ConflictException;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Durable, cooperative stop at node boundaries. Never cancels an in-flight external effect. */
@Service
public class WorkflowStopService {
    private final JdbcTemplate db;
    private final WorkflowExecutionRepository executions;
    private final WorkflowSchedulingService scheduling;
    private final WorkflowExecutionProperties properties;
    private final AuditLogRepository audits;
    private final TransactionTemplate tx;

    public WorkflowStopService(JdbcTemplate db, WorkflowExecutionRepository executions,
            WorkflowSchedulingService scheduling, WorkflowExecutionProperties properties,
            AuditLogRepository audits, PlatformTransactionManager manager) {
        this.db = db; this.executions = executions; this.scheduling = scheduling;
        this.properties = properties; this.audits = audits;
        tx = new TransactionTemplate(manager);
        tx.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public record Request(@NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{1,64}") String requestKey) {}
    public record Report(String contract, boolean supported, String mode, String state, String executionStatus,
                         String requestKey, String requestedBy, Long requestedMs, Long appliedMs,
                         boolean effectsRolledBack) {}
    private record Intent(String key, String principalHash, String requester, long requestedMs, Long appliedMs) {}

    @Transactional
    public Report request(Long workflowId, String runKey, Request request, String requester) {
        if (request == null || request.requestKey() == null || !request.requestKey().matches("[A-Za-z0-9_-]{1,64}"))
            throw new BusinessException("requestKey 必须是 1–64 位字母、数字、下划线或短横线");
        if (requester == null || requester.isBlank()) throw new BusinessException("停止操作必须包含调用者身份");
        // Same order as queue claim/submission. A queued execution cannot be claimed while stopped.
        scheduling.lockEnrollment();
        Long id = executions.findExecutionId(workflowId, runKey).orElseThrow(() -> new BusinessException("执行记录不存在"));
        var run = executions.lockById(id).orElseThrow();
        Intent existing = intent(run.id);
        if (existing != null) {
            if (!existing.key.equals(request.requestKey()) || !existing.principalHash.equals(WorkflowSchedulingService.groupKey(requester)))
                throw new ConflictException("此执行已有停止请求，请查询原请求；不能更换请求键或调用者重复提交");
            return report(run, existing);
        }
        if (run.stopVersion != 1 || run.recoveryVersion != 1)
            throw new ConflictException("此历史执行未加入停止协议，原执行状态保持");
        if (!Set.of("QUEUED", "RUNNING").contains(run.status))
            throw new ConflictException("执行已结束或结果未知，请核对原执行；不能通过停止操作改写结果");
        if ("RUNNING".equals(run.status) && !validOwnerTime(run, Instant.now()))
            throw new ConflictException("执行责任已过期，请核对原任务结果，停止操作不能证明外部任务已结束");
        long now = Instant.now().toEpochMilli();
        String display = requester.substring(0, Math.min(requester.length(), 128));
        db.update("insert into workflow_stop_requests(execution_id,request_key,requester_hash,requested_by,requested_ms) values (?,?,?,?,?)",
                run.id, request.requestKey(), WorkflowSchedulingService.groupKey(requester), display, now);
        if ("QUEUED".equals(run.status)) apply(run, now);
        var log = new AuditLog();
        log.setActor(display); log.setAction("WORKFLOW_STOP_REQUESTED"); log.setResourceType("WORKFLOW_EXECUTION");
        log.setResourceId(run.id.toString()); log.setResult("ACCEPTED");
        log.setTraceId(UUID.randomUUID().toString()); log.setCreatedAt(Instant.ofEpochMilli(now)); audits.save(log);
        return report(run, intent(run.id));
    }

    @Transactional(readOnly = true)
    public Report get(Long workflowId, String runKey) {
        var run = find(workflowId, runKey);
        return report(run, intent(run.id));
    }

    /** Called after the previous step has durably recorded its receipt and reclaim outcome. */
    boolean checkpoint(WorkflowExecution original) {
        if (original.stopVersion != 1) return true;
        return Boolean.TRUE.equals(tx.execute(ignored -> {
            var run = executions.lockById(original.id).orElseThrow();
            if (!"RUNNING".equals(run.status) || run.ownerEpoch != original.ownerEpoch
                    || !Objects.equals(run.ownerId, original.ownerId) || !validOwnerTime(run, Instant.now())) return false;
            if (intent(run.id) == null) return true;
            apply(run, Instant.now().toEpochMilli());
            return false;
        }));
    }

    private boolean validOwnerTime(WorkflowExecution run, Instant now) {
        return run.ownerId != null && run.startedAt != null && now.isBefore(run.startedAt.plusSeconds(properties.getTimeoutSeconds()));
    }
    private void apply(WorkflowExecution run, long now) {
        // Preserve steps, original effects, budget reservations and task/operation identities.
        run.status = "CANCELLED"; run.reason = "已停止后续调度；已开始步骤的结果保留，外部效果与预算未回滚";
        run.completedAt = Instant.ofEpochMilli(now); run.ownerEpoch++; run.ownerId = null;
        executions.saveAndFlush(run);
        if (db.update("update workflow_stop_requests set applied_ms=? where execution_id=? and applied_ms is null", now, run.id) != 1)
            throw new IllegalStateException("Stop request already applied");
    }
    private WorkflowExecution find(Long workflowId, String runKey) {
        return executions.findByWorkflowIdAndRunKey(workflowId, runKey).orElseThrow(() -> new BusinessException("执行记录不存在"));
    }
    private Intent intent(Long id) {
        return db.query("select request_key,requester_hash,requested_by,requested_ms,applied_ms from workflow_stop_requests where execution_id=?",
                (r, n) -> new Intent(r.getString(1), r.getString(2), r.getString(3), r.getLong(4),
                        r.getObject(5) == null ? null : r.getLong(5)), id).stream().findFirst().orElse(null);
    }
    private Report report(WorkflowExecution run, Intent intent) {
        boolean supported = run.stopVersion == 1 && run.recoveryVersion == 1;
        String state = !supported ? "UNSUPPORTED" : intent == null ? "NONE"
                : intent.appliedMs != null ? "APPLIED"
                : "EXECUTION_UNKNOWN".equals(run.status) || ("RUNNING".equals(run.status) && !validOwnerTime(run, Instant.now())) ? "UNCONFIRMED"
                : Set.of("QUEUED", "RUNNING").contains(run.status) ? "REQUESTED" : "FINISHED";
        return new Report("EAP_WORKFLOW_STOP_V1", supported, "AFTER_CURRENT_STEP", state, run.status,
                intent == null ? null : intent.key, intent == null ? null : intent.requester,
                intent == null ? null : intent.requestedMs, intent == null ? null : intent.appliedMs, false);
    }
}
