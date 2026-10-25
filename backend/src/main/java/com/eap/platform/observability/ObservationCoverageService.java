package com.eap.platform.observability;

import com.eap.platform.common.BusinessException;
import com.eap.platform.config.SecurityProperties;
import com.eap.platform.registry.AgentRuntimeInstanceRepository;
import com.eap.platform.registry.AgentRuntimeInstance;
import com.eap.platform.registry.RuntimeScopedTelemetry;
import com.eap.platform.workflow.WorkflowDtos;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;

/** Persistent denominators and metadata logs. Observation completeness never changes execution or spend. */
@Service
public class ObservationCoverageService {
    private static final List<String> ENTRIES = List.of("AX_ECHO", "AX_FAIL", "AX_LLM", "EXPERIMENT", "WORKFLOW_CONTROL");
    private final JdbcTemplate db;
    private final AgentRuntimeInstanceRepository runtimes;
    private final SecurityProperties security;
    @org.springframework.beans.factory.annotation.Autowired
    private RuntimePlacementEvidenceStore placementEvidence;
    @org.springframework.beans.factory.annotation.Value("${eap.observability.runtime-denominators-enabled:false}")
    private boolean runtimeDenominatorsEnabled;
    public ObservationCoverageService(JdbcTemplate db, AgentRuntimeInstanceRepository runtimes, SecurityProperties security) {
        this.db = db; this.runtimes = runtimes; this.security = security;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void enrollAx(long task, String rawTrace, String mode) {
        if (!Set.of("ECHO", "FAIL", "LLM").contains(mode)) throw new BusinessException("AX 观测模式无效");
        var t = db.queryForMap("select task_key,agent_id,instance_key,identity_mode from ax_tasks where id=?", task);
        String trace = t.get("instance_key") == null ? rawTrace : RuntimeScopedTelemetry.trace((String)t.get("instance_key"), rawTrace);
        db.update("insert into observation_operations(entry_kind,operation_key,ax_task_id,agent_id,raw_trace_id,trace_id,instance_key,registration_state,expected_agent_spans,expected_model_events,span_name,created_at) values(?,?,?,?,?,?,?,'PENDING_IDENTITY',1,?,'ax.task',CURRENT_TIMESTAMP)",
                "AX_" + mode, t.get("task_key"), task, t.get("agent_id"), rawTrace, trace, t.get("instance_key"), "LLM".equals(mode) ? 1 : 0);
        event(id((String)t.get("task_key")), "REGISTERED", "ORIGINAL_INTENT_FROZEN", null);
    }

    /** Called with the original task row locked, after the existing identity check and before the first HTTP dispatch. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void axDispatch(long task) {
        var rows = db.queryForList("select o.*,t.actor_uid current_uid,t.actor_template current_template,t.identity_mode from observation_operations o join ax_tasks t on t.id=o.ax_task_id where t.id=? for update", task);
        if (rows.isEmpty()) return; // Historical task: never invent an enrollment on read or dispatch.
        var o = rows.getFirst(); long id = number(o, "id");
        if ("PENDING_IDENTITY".equals(o.get("registration_state")) && "PER_INSTANCE_V1".equals(o.get("identity_mode"))) {
            var runtime = runtimes.findByInstanceKey((String)o.get("instance_key")).orElse(null);
            if (runtime != null && runtime.supportsMachineIdentity() && Objects.equals(runtime.getAgentId(), o.get("agent_id"))
                    && Objects.equals(runtime.getBoundActorUid(), o.get("current_uid"))
                    && Objects.equals(runtime.getBoundActorTemplate(), o.get("current_template"))) {
                db.update("update observation_operations set runtime_id=?,endpoint=?,actor_uid=?,actor_template=?,registration_state='CONFIRMED' where id=? and registration_state='PENDING_IDENTITY'",
                        runtime.getId(), runtime.getEndpoint(), runtime.getBoundActorUid(), runtime.getBoundActorTemplate(), id);
                event(id, "IDENTITY_FROZEN", "ORIGINAL_CREATE_RECEIPT_MATCH", null);
            }
        }
        event(id, "DISPATCH_COMMITTED", "ORIGINAL_DISPATCH_ONLY", null);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void axReceipt(long task, JsonNode receipt) {
        var rows = db.queryForList("select id,raw_trace_id from observation_operations where ax_task_id=?", task);
        if (rows.isEmpty()) return;
        var o = rows.getFirst();
        String raw = receipt.hasNonNull("runtimeTraceId") ? receipt.path("runtimeTraceId").asText() : receipt.path("traceId").asText();
        event(number(o, "id"), "RECEIPT_CONFIRMED", Objects.equals(o.get("raw_trace_id"), raw)
                ? "ORIGINAL_TRACE_MATCH" : "RECEIPT_TRACE_UNCONFIRMED", null);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void enrollExperiment(long experiment) {
        for (var d : db.queryForList("select d.*,t.task_key from experiment_diagnostic_decisions d join agent_experiment_trials t on t.id=d.trial_id where t.experiment_id=? order by t.id", experiment)) {
            db.update("insert into observation_operations(entry_kind,operation_key,trial_id,agent_id,raw_trace_id,trace_id,runtime_id,instance_key,endpoint,actor_uid,actor_template,registration_state,expected_agent_spans,expected_model_events,span_name,created_at) values('EXPERIMENT',?,?,?,?,?,?,?,?,?,?,?,1,1,'agent.experiment',CURRENT_TIMESTAMP)",
                    d.get("task_key"), d.get("trial_id"), d.get("agent_id"), d.get("trace_id"), d.get("trace_id"), d.get("runtime_id"), d.get("instance_key"), d.get("endpoint"), d.get("actor_uid"), d.get("actor_template"), Boolean.TRUE.equals(d.get("identity_confirmed")) ? "CONFIRMED" : "IDENTITY_UNCONFIRMED");
            event(id((String)d.get("task_key")), "REGISTERED", "ORIGINAL_INTENT_FROZEN", null);
        }
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void experimentEvent(long trial, boolean dispatched) {
        var rows = db.queryForList("select id from observation_operations where trial_id=?", trial);
        if (!rows.isEmpty()) event(number(rows.getFirst(), "id"), dispatched ? "DISPATCH_COMMITTED" : "RECEIPT_CONFIRMED",
                dispatched ? "ORIGINAL_DISPATCH_ONLY" : "PLATFORM_RECEIPT_RECORDED", null);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void enrollControls(long execution, String run, List<WorkflowDtos.NodeResponse> path) {
        for (int i = 0; i < path.size(); i++) {
            var node = path.get(i);
            if (!Set.of("START", "END", "DECISION").contains(node.nodeType().toUpperCase(Locale.ROOT))) continue;
            String key = run + ":control:" + i, trace = UUID.randomUUID().toString().replace("-", "");
            db.update("insert into observation_operations(entry_kind,operation_key,workflow_execution_id,node_sequence,trace_id,registration_state,expected_agent_spans,expected_model_events,created_at) values('WORKFLOW_CONTROL',?,?,?,?,'PLATFORM_CHECK',0,0,CURRENT_TIMESTAMP)", key, execution, i, trace);
            event(id(key), "REGISTERED", "ORIGINAL_CONTROL_ROUTE_FROZEN", null);
        }
    }

    /** Freeze ordinary task expectations with the accepted route, including tasks never reached. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void enrollRuntimeTask(long execution, int sequence, String key, Long agent, int expectedModels) {
        if (expectedModels < 0 || expectedModels > 1) throw new BusinessException("模型观测预期只能为0或1");
        if (!runtimeDenominatorsEnabled) return;
        String raw = UUID.randomUUID().toString().replace("-", "");
        db.update("insert into observation_operations(entry_kind,operation_key,workflow_execution_id,node_sequence,agent_id,raw_trace_id,registration_state,expected_agent_spans,expected_model_events,span_name,created_at) values('RUNTIME_TASK',?,?,?,?,?,'PENDING_IDENTITY',1,?,'agent.task',CURRENT_TIMESTAMP(6))",
                key, execution, sequence, agent, raw, expectedModels);
        event(id(key), "REGISTERED", "ORIGINAL_CONTROL_ROUTE_FROZEN", null);
    }

    /** Called only by the durable chat admission winner; receipt queries/replays do not enroll new operations. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public String enrollChat(AgentRuntimeInstance runtime) {
        if (!runtimeDenominatorsEnabled) return null;
        String key = "chat-" + UUID.randomUUID(), raw = UUID.randomUUID().toString().replace("-", "");
        db.update("insert into observation_operations(entry_kind,operation_key,agent_id,raw_trace_id,registration_state,expected_agent_spans,expected_model_events,span_name,runtime_status,created_at) values('CHAT',?,?,?,'PENDING_IDENTITY',1,1,'agent.turn','PENDING',CURRENT_TIMESTAMP(6))",
                key, runtime.getAgentId(), raw);
        event(id(key), "REGISTERED", "ORIGINAL_INTENT_FROZEN", null);
        return key;
    }

    /** Invoked only after existing identity/readiness checks and immediately before the HTTP send. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public String runtimeDispatch(String key, AgentRuntimeInstance runtime, Instant deadline) {
        if (key == null) return null;
        var rows = db.queryForList("select * from observation_operations where operation_key=? and entry_kind in ('RUNTIME_TASK','CHAT') for update", key);
        if (rows.isEmpty()) return null; // Historical operation: no enrollment or repair on dispatch.
        var o = rows.getFirst(); long id = number(o, "id");
        if (!Objects.equals(o.get("agent_id"), runtime.getAgentId())) throw new BusinessException("观测原任务与派发智能体不匹配");
        if (db.queryForObject("select count(*) from observation_operation_events where operation_id=? and event_kind='DISPATCH_COMMITTED'", Long.class, id) != 0)
            throw new BusinessException("原任务已登记派发，不能重新发送");
        String raw = (String)o.get("raw_trace_id");
        boolean bound = runtime.supportsMachineIdentity();
        if (deadline == null || !deadline.isAfter(Instant.now())) throw new BusinessException("原派发期限已到");
        db.update("update observation_operations set runtime_id=?,instance_key=?,endpoint=?,actor_uid=?,actor_template=?,trace_id=?,registration_state=?,runtime_status='RUNNING',dispatch_deadline=? where id=?",
                runtime.getId(), runtime.getInstanceKey(), runtime.getEndpoint(), runtime.getBoundActorUid(), runtime.getBoundActorTemplate(),
                bound ? RuntimeScopedTelemetry.trace(runtime.getInstanceKey(), raw) : raw,
                bound ? "CONFIRMED" : "IDENTITY_UNCONFIRMED", Timestamp.from(deadline), id);
        event(id, "DISPATCH_COMMITTED", "ORIGINAL_DISPATCH_ONLY", null);
        return raw;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void runtimeReceipt(String key, String status, String rawTrace) {
        if (key == null) return;
        if (!Set.of("SUCCEEDED", "FAILED", "EXECUTION_UNKNOWN").contains(status)) throw new BusinessException("观测回执状态无效");
        var rows = db.queryForList("select * from observation_operations where operation_key=? and entry_kind in ('RUNTIME_TASK','CHAT') for update", key);
        if (rows.isEmpty()) return;
        var o = rows.getFirst(); long id = number(o, "id");
        if (db.queryForObject("select count(*) from observation_operation_events where operation_id=? and event_kind='RECEIPT_CONFIRMED'", Long.class, id) != 0) return;
        db.update("update observation_operations set runtime_status=? where id=?", status, id);
        event(id, "RECEIPT_CONFIRMED", "EXECUTION_UNKNOWN".equals(status) ? "OUTCOME_UNCONFIRMED"
                : Objects.equals(o.get("raw_trace_id"), rawTrace) ? "ORIGINAL_TRACE_MATCH" : "RECEIPT_TRACE_UNCONFIRMED", null);
    }

    /** Caller has already passed progressOwned CAS; this event commits in the same transaction. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void controlsProgress(long execution, long epoch, List<com.eap.platform.workflow.WorkflowExecutionService.Step> steps) {
        for (var step : steps) if ("CHECK_PASSED".equals(step.status())) {
            var rows = db.queryForList("select id from observation_operations where workflow_execution_id=? and node_sequence=?", execution, step.sequence());
            if (!rows.isEmpty()) event(number(rows.getFirst(), "id"), "CONTROL_CHECKED", "PLATFORM_CHECK_PASSED", epoch);
        }
    }

    private long id(String key) { return db.queryForObject("select id from observation_operations where operation_key=?", Long.class, key); }
    private void event(long id, String kind, String reason, Long epoch) {
        // All call sites hold the original admission/task/execution lock. No retry or caller text is recorded.
        if (db.queryForObject("select count(*) from observation_operation_events where operation_id=? and event_kind=?", Long.class, id, kind) != 0) return;
        db.update("insert into observation_operation_events values(?,?,?,?,CURRENT_TIMESTAMP)", id, kind, reason, epoch);
        var metadata = db.queryForMap("select entry_kind,trace_id from observation_operations where id=?", id);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() {
                LoggerFactory.getLogger(ObservationCoverageService.class).info("observation operation_id={} entry={} trace={} event={} reason={}", id, metadata.get("entry_kind"), metadata.get("trace_id"), kind, reason);
            }
        });
    }

    public record Operation(long id, String entryKind, String operationKey, Long agentId, String traceId,
            String identityEvidence, String executionStatus, String coverage, Integer expectedAgentSpans, Integer expectedModelEvents,
            Long agentSpans, Long modelEvents, Long successfulModelEvents, long createdMs, String originalPath) {}
    public record Metric(String entryKind, Long registered, long complete, long pending, long gaps, long unconfirmed,
            long failed, long unknown, Long expectedAgentSpans, Long expectedModelEvents) {}
    public record Report(int windowMinutes, long windowStartMs, long observedMs, String denominatorContract,
            List<Metric> metrics, List<Operation> recent, int detailLimit, boolean detailsTruncated) {}
    public record Event(String kind, String reasonCode, Long ownerEpoch, long createdMs) {}
    public record Cost(long reservationId, String state, Long inputTokens, Long outputTokens) {}
    public record Detail(Operation operation, List<Event> logs, List<Cost> costs,
                         List<RuntimePlacementEvidenceStore.Evidence> placements) {}
    public record Fact(Operation operation, String digest) {}

    /** Digest only the original metadata projection. No request text, credentials or read clock enters it. */
    @Transactional(readOnly = true)
    public Fact fact(long id) {
        var rows = db.queryForList(PROJECTION + "where " + visibility() + " and o.id=?", id);
        if (rows.isEmpty()) throw new BusinessException("观测登记不存在");
        var row = rows.getFirst();
        var operation = project(row);
        var fields = new TreeMap<String, Object>(row);
        fields.put("projected_status", operation.executionStatus());
        fields.put("projected_coverage", operation.coverage());
        try {
            var digest = java.security.MessageDigest.getInstance("SHA-256");
            for (var entry : fields.entrySet()) {
                String value = entry.getValue() == null ? "N" : "V" + (entry.getValue() instanceof Timestamp stamp
                        ? stamp.toInstant().toString() : entry.getValue().toString());
                for (String part : List.of(entry.getKey(), value)) {
                    byte[] bytes = part.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                    digest.update(java.nio.ByteBuffer.allocate(4).putInt(bytes.length).array());
                    digest.update(bytes);
                }
            }
            return new Fact(operation, HexFormat.of().formatHex(digest.digest()));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("观测事实摘要不可用");
        }
    }

    private static final String PROJECTION = "select o.*,coalesce(o.runtime_status,t.status,x.status,w.status,'UNKNOWN') execution_status,"
            + "r.agent_id current_agent,r.instance_key current_key,r.endpoint current_endpoint,coalesce(r.imported_actor_uid,r.platform_actor_uid) current_uid,coalesce(r.imported_actor_template,r.platform_actor_template) current_template,r.protocol current_protocol,"
            + "(select count(*) from trace_span_observations s where s.trace_id=o.trace_id and s.agent_id=o.agent_id and s.endpoint_key=concat('runtime-',o.runtime_id) and s.span_kind='AGENT' and s.span_name=o.span_name) agent_count,"
            + "(select count(*) from model_invocation_events m where m.trace_id=o.trace_id and m.agent_id=o.agent_id and m.endpoint_key=concat('runtime-',o.runtime_id) and m.observation_source='GATEWAY') model_count,"
            + "(select count(*) from model_invocation_events m where m.trace_id=o.trace_id and m.agent_id=o.agent_id and m.endpoint_key=concat('runtime-',o.runtime_id) and m.observation_source='GATEWAY' and m.status='SUCCEEDED') model_success,"
            + "(select reason_code from observation_operation_events e where e.operation_id=o.id and e.event_kind='RECEIPT_CONFIRMED') receipt_reason,"
            + "(select count(*) from observation_operation_events e where e.operation_id=o.id and e.event_kind='CONTROL_CHECKED') control_checked,"
            + "(select count(*) from observation_operation_events e where e.operation_id=o.id and e.event_kind='DISPATCH_COMMITTED') dispatched,"
            + "t.experiment_id,w.workflow_id,w.run_key "
            + "from observation_operations o left join agent_experiment_trials t on t.id=o.trial_id left join ax_tasks x on x.id=o.ax_task_id "
            + "left join workflow_executions w on w.id=o.workflow_execution_id left join agent_runtime_instances r on r.id=o.runtime_id left join agents a on a.id=o.agent_id ";
    String visibility() { return security.isDevelopmentMode() ? "1=1" : "(o.agent_id is null or a.record_origin<>'DEMO')"; }

    /** Project only the bounded, execution-scoped page selected by the metadata reader. */
    List<Operation> operations(List<Long> ids) {
        if (ids.isEmpty()) return List.of();
        if (ids.size() > 50) throw new IllegalArgumentException("Observation page exceeds limit");
        String placeholders = String.join(",", Collections.nCopies(ids.size(), "?"));
        return db.queryForList(PROJECTION + "where " + visibility() + " and o.id in (" + placeholders + ") order by o.id desc", ids.toArray())
                .stream().map(this::project).toList();
    }

    // Cursor scans use exactly the same visibility as fact/report, with no age cutoff.
    Long highestVisibleOperation() {
        return db.queryForObject("select max(o.id) from observation_operations o left join agents a on a.id=o.agent_id where " + visibility(), Long.class);
    }
    Long nextVisibleOperation(long after, long upper) {
        var ids = db.queryForList("select o.id from observation_operations o left join agents a on a.id=o.agent_id where "
                + visibility() + " and o.id>? and o.id<=? order by o.id limit 1", Long.class, after, upper);
        return ids.isEmpty() ? null : ids.getFirst();
    }

    @Transactional(readOnly = true)
    public Report report(int minutes) {
        if (minutes < 1 || minutes > 10080) throw new BusinessException("观测窗口必须为1–10080分钟");
        long now = Instant.now().toEpochMilli(), start = now - minutes * 60000L;
        var rows = db.queryForList(PROJECTION + "where " + visibility() + " and o.created_at>=? order by o.id desc limit 10001", new Timestamp(start));
        if (rows.size() > 10000) throw new BusinessException("登记数量超过10000，请缩短观测窗口");
        var operations = rows.stream().map(this::project).toList();
        List<Metric> metrics = new ArrayList<>();
        List<String> kinds = new ArrayList<>(ENTRIES);
        if (runtimeDenominatorsEnabled || operations.stream().anyMatch(o -> Set.of("RUNTIME_TASK", "CHAT").contains(o.entryKind()))) kinds.addAll(List.of("RUNTIME_TASK", "CHAT"));
        for (String kind : kinds) {
            var entry = operations.stream().filter(o -> kind.equals(o.entryKind())).toList();
            long complete = entry.stream().filter(o -> "COMPLETE".equals(o.coverage())).count();
            long pending = entry.stream().filter(o -> Set.of("PENDING", "NOT_EXPECTED", "NOT_EXECUTED").contains(o.coverage())).count();
            long unconfirmed = entry.stream().filter(o -> o.coverage().contains("UNCONFIRMED")).count();
            metrics.add(new Metric(kind, (long)entry.size(), complete, pending, entry.size() - complete - pending - unconfirmed, unconfirmed,
                    entry.stream().filter(o -> "FAILED".equals(o.executionStatus())).count(),
                    entry.stream().filter(o -> Set.of("UNKNOWN", "EXECUTION_UNKNOWN").contains(o.executionStatus())).count(),
                    entry.stream().mapToLong(o -> o.expectedAgentSpans()).sum(), entry.stream().mapToLong(o -> o.expectedModelEvents()).sum()));
        }
        // Received spans are never substituted for a persistent original-request denominator.
        for (String entry : List.of("RUNTIME_TASK", "CHAT", "LEGACY_EXTERNAL")) if (!kinds.contains(entry)) metrics.add(new Metric(entry, null, 0, 0, 0, 0, 0, 0, null, null));
        return new Report(minutes, start, now, "ORIGINAL_SUBMISSION_V1", metrics, operations.stream().limit(100).toList(), 100, operations.size() > 100);
    }

    @Transactional(readOnly = true)
    public Detail detail(long id) {
        var rows = db.queryForList(PROJECTION + "where " + visibility() + " and o.id=?", id);
        if (rows.isEmpty()) throw new BusinessException("观测登记不存在");
        var row = rows.getFirst();
        var logs = db.query("select event_kind,reason_code,owner_epoch,created_at from observation_operation_events where operation_id=? order by created_at,event_kind", (r, n) -> new Event(r.getString(1), r.getString(2), (Long)r.getObject(3), r.getTimestamp(4).toInstant().toEpochMilli()), id);
        var costs = db.query("select id,state,input_tokens,output_tokens from budget_reservations where trace_id=? and agent_id=? order by id", (r, n) -> new Cost(r.getLong(1), r.getString(2), (Long)r.getObject(3), (Long)r.getObject(4)), row.get("trace_id"), row.get("agent_id"));
        return new Detail(project(row), logs, costs, placementEvidence.recorded(id));
    }

    private Operation project(Map<String,Object> o) {
        String kind = (String)o.get("entry_kind"), status = (String)o.get("execution_status"), identity, coverage;
        if ("RUNNING".equals(status) && o.get("dispatch_deadline") instanceof Timestamp deadline && !deadline.toInstant().isAfter(Instant.now())) status = "EXECUTION_UNKNOWN";
        long agent = number(o, "agent_count"), model = number(o, "model_count"), successful = number(o, "model_success");
        int expectedAgent = ((Number)o.get("expected_agent_spans")).intValue(), expectedModel = ((Number)o.get("expected_model_events")).intValue();
        boolean control = "WORKFLOW_CONTROL".equals(kind);
        boolean confirmed = "CONFIRMED".equals(o.get("registration_state"))
                && Objects.equals(o.get("agent_id"), o.get("current_agent")) && Objects.equals(o.get("instance_key"), o.get("current_key"))
                && Objects.equals(o.get("endpoint"), o.get("current_endpoint")) && Objects.equals(o.get("actor_uid"), o.get("current_uid"))
                && Objects.equals(o.get("actor_template"), o.get("current_template"))
                && Set.of("SUBSTRATE", "SUBSTRATE_TASK", "SUBSTRATE_OBSERVED").contains(Objects.toString(o.get("current_protocol"), ""));
        identity = control ? "PLATFORM_CHECK" : confirmed ? "FROZEN_REGISTRATION_MATCH" : "IDENTITY_UNCONFIRMED";
        if (control) coverage = number(o, "control_checked") == 1 ? "COMPLETE" : terminal(status) ? "NOT_EXECUTED" : "PENDING";
        else if (Set.of("RUNTIME_TASK", "CHAT").contains(kind) && "PENDING_IDENTITY".equals(o.get("registration_state")) && number(o, "dispatched") == 0) coverage = terminal(status) ? "NOT_EXECUTED" : "PENDING";
        else if (!confirmed) coverage = "IDENTITY_UNCONFIRMED";
        else if ("RECEIPT_TRACE_UNCONFIRMED".equals(o.get("receipt_reason"))) coverage = "RECEIPT_TRACE_UNCONFIRMED";
        else if (agent > expectedAgent || model > expectedModel) coverage = expectedModel == 0 && model > 0 ? "UNEXPECTED_MODEL" : "DUPLICATE_EVIDENCE";
        else if (number(o, "dispatched") == 0) coverage = terminal(status) ? "NOT_EXPECTED" : "PENDING";
        else if (!terminal(status)) coverage = "PENDING";
        else if (model == expectedModel && agent == expectedAgent) coverage = model == successful || !"SUCCEEDED".equals(status) ? "COMPLETE" : "MODEL_STATUS_UNCONFIRMED";
        else coverage = agent < expectedAgent && model < expectedModel ? "MISSING_BOTH" : agent < expectedAgent ? "MISSING_AGENT" : "MISSING_MODEL";
        String path = control || "RUNTIME_TASK".equals(kind) ? "/workflow-builder?workflowId=" + o.get("workflow_id") : "EXPERIMENT".equals(kind) ? "/learning?agentId=" + o.get("agent_id") : "CHAT".equals(kind) ? "/agents/" + o.get("agent_id") : "/ax-tasks?taskKey=" + o.get("operation_key");
        Long agentCount = null, modelCount = null, successfulCount = null;
        if (control || confirmed) {
            agentCount = control ? 0L : agent;
            modelCount = control ? 0L : model;
            successfulCount = control ? 0L : successful;
        }
        return new Operation(number(o, "id"), kind, (String)o.get("operation_key"), (Long)o.get("agent_id"), (String)o.get("trace_id"), identity, status, coverage,
                expectedAgent, expectedModel, agentCount, modelCount, successfulCount,
                ((Timestamp)o.get("created_at")).toInstant().toEpochMilli(), path);
    }
    private static long number(Map<String,Object> row, String key) { return ((Number)row.get(key)).longValue(); }
    private static boolean terminal(String status) { return Set.of("SUCCEEDED", "FAILED", "UNKNOWN", "EXECUTION_UNKNOWN", "CANCELLED", "BLOCKED", "WAITING_APPROVAL", "COMPLETED").contains(status); }
}
