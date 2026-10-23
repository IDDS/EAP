package com.eap.platform.observability;

import com.eap.platform.common.BusinessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Read original persisted associations only. No dispatch, enrollment, repair or age window. */
@Service
@Transactional(readOnly = true)
public class WorkflowObservationService {
    public static final String CONTRACT = "WORKFLOW_OBSERVATIONS_V1";
    private final JdbcTemplate db;
    private final ObservationCoverageService coverage;
    public WorkflowObservationService(JdbcTemplate db, ObservationCoverageService coverage) {
        this.db = db; this.coverage = coverage;
    }
    public record Entry(ObservationCoverageService.Operation operation, Integer nodeSequence, String nodeKey) {}
    public record Page(String contract, long workflowId, String runKey, List<Entry> entries, String nextCursor) {}
    public record Detail(long workflowId, String runKey, ObservationCoverageService.Detail detail) {}
    private record Reference(long id, Integer sequence, String nodeKey) {}

    public Page page(long workflowId, String runKey, int limit, String cursor) {
        if (limit < 1 || limit > 50) throw new BusinessException("证据分页大小必须为1–50");
        long execution = execution(workflowId, runKey), before = before(cursor, execution);
        var refs = references(execution, workflowId, runKey, before, null, limit + 1);
        boolean more = refs.size() > limit;
        var selected = refs.stream().limit(limit).toList();
        var byId = new HashMap<Long, Reference>();
        selected.forEach(r -> byId.put(r.id(), r));
        var entries = coverage.operations(selected.stream().map(Reference::id).toList()).stream()
                .map(o -> new Entry(o, byId.get(o.id()).sequence(), byId.get(o.id()).nodeKey())).toList();
        String next = more ? encode(execution, selected.getLast().id()) : null;
        return new Page(CONTRACT, workflowId, runKey, entries, next);
    }

    public Detail detail(long workflowId, String runKey, long id) {
        long execution = execution(workflowId, runKey);
        if (id <= 0 || references(execution, workflowId, runKey, Long.MAX_VALUE, id, 1).isEmpty())
            throw new BusinessException("原执行的观测登记不存在");
        return new Detail(workflowId, runKey, coverage.detail(id));
    }

    private long execution(long workflowId, String runKey) {
        var ids = db.queryForList("select id from workflow_executions where workflow_id=? and run_key=?", Long.class, workflowId, runKey);
        if (ids.isEmpty()) throw new BusinessException("执行记录不存在");
        return ids.getFirst();
    }

    private List<Reference> references(long execution, long workflow, String run, long before, Long exact, int limit) {
        // The FK indexes and idx_ax_workflow_run restrict both branches before telemetry projection.
        // AX uses its durable parent fields, including when workflow progress has not recorded its receipt yet.
        String filter = " and " + coverage.visibility() + " and o.id<?" + (exact == null ? "" : " and o.id=?");
        String sql = "select o.id,o.node_sequence,null node_key from observation_operations o left join agents a on a.id=o.agent_id "
                + "where o.workflow_execution_id=? and o.entry_kind in ('WORKFLOW_CONTROL','RUNTIME_TASK')" + filter
                + " union all select o.id,null node_sequence,x.workflow_node_key node_key from ax_tasks x "
                + "join observation_operations o on o.ax_task_id=x.id and o.operation_key=x.task_key left join agents a on a.id=o.agent_id "
                + "where x.workflow_id=? and x.workflow_run_key=? and o.entry_kind in ('AX_ECHO','AX_FAIL','AX_LLM') "
                + "and (o.workflow_execution_id is null or o.workflow_execution_id=?)" + filter
                + " order by id desc limit ?";
        List<Object> args = new ArrayList<>(List.of(execution, before));
        if (exact != null) args.add(exact);
        args.addAll(List.of(workflow, run, execution, before));
        if (exact != null) args.add(exact);
        args.add(limit);
        return db.query(sql, (r, n) -> new Reference(r.getLong("id"), (Integer)r.getObject("node_sequence"), r.getString("node_key")), args.toArray());
    }

    // Cursor is a position, never authorization. Every request rechecks the path scope and visibility.
    private static long before(String cursor, long execution) {
        if (cursor == null) return Long.MAX_VALUE;
        try {
            if (!cursor.matches("[A-Za-z0-9_-]{1,80}")) throw new IllegalArgumentException();
            String[] parts = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.US_ASCII).split(":", -1);
            if (parts.length != 3 || !"1".equals(parts[0]) || Long.parseLong(parts[1]) != execution) throw new IllegalArgumentException();
            long before = Long.parseLong(parts[2]);
            if (before <= 0 || !encode(execution, before).equals(cursor)) throw new IllegalArgumentException();
            return before;
        } catch (IllegalArgumentException e) { throw new BusinessException("证据游标无效或不属于原执行，请刷新"); }
    }
    private static String encode(long execution, long before) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(("1:" + execution + ":" + before).getBytes(StandardCharsets.US_ASCII));
    }
}
