package com.eap.platform.operations;

import com.eap.platform.common.BusinessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** Read-only joins using explicit task_id, never proximity or shared Agent identity. */
@Service
public class TaskEvidenceService {
    private static final int LIMIT = 100;
    private final AgentTaskRepository tasks;
    private final JdbcTemplate jdbc;
    public TaskEvidenceService(AgentTaskRepository tasks, JdbcTemplate jdbc) { this.tasks = tasks; this.jdbc = jdbc; }
    public record Evidence(long id, String key, String traceId, Long agentId, String label, String status,
                           Instant observedAt, BigDecimal amount, String currency) {}
    public record Section(String source, List<Evidence> records, boolean hasMore, long conflictingAgentRecords) {}
    public record Response(AgentTaskDtos.TaskResponse task, String associationBasis, List<Section> sections) {}

    @Transactional(readOnly = true)
    public Response get(long id) {
        AgentTask task = tasks.findById(id).orElseThrow(() -> new BusinessException("运行任务不存在"));
        return new Response(AgentTaskDtos.from(task), "EXPLICIT_TASK_ID", List.of(
            read("MODEL", "model_invocation_events", "event_key", "trace_id", "model_name", "status", "money_cost", "currency", task),
            read("TOOL", "tool_invocation_events", "invocation_key", "trace_id", "tool_name", "status", "NULL", "NULL", task),
            read("GOVERNANCE", "governance_data_events", "event_key", "trace_id", "data_category", "action", "NULL", "NULL", task),
            read("VALUE", "management_value_events", "event_key", "NULL", "value_type", "source_type", "benefit_money", "currency", task)
        ));
    }

    // Identifiers are internal constants only. All request data is parameterized.
    private Section read(String source, String table, String key, String trace, String label, String status,
                         String amount, String currency, AgentTask task) {
        String columns = "id, " + key + " AS evidence_key, " + trace + " AS trace_id, agent_id, " + label
                + " AS label, " + status + " AS status, observed_at, " + amount + " AS amount, " + currency + " AS currency";
        List<Evidence> found = jdbc.query("SELECT " + columns + " FROM " + table
                + " WHERE task_id = ? AND (agent_id IS NULL OR agent_id = ?) ORDER BY observed_at DESC, id DESC LIMIT ?",
            (rs, index) -> new Evidence(rs.getLong("id"), rs.getString("evidence_key"), rs.getString("trace_id"),
                rs.getObject("agent_id", Long.class), rs.getString("label"), rs.getString("status"),
                rs.getTimestamp("observed_at") == null ? null : rs.getTimestamp("observed_at").toInstant(),
                rs.getBigDecimal("amount"), rs.getString("currency")), task.id, task.agentId, LIMIT + 1);
        Long conflicts = jdbc.queryForObject("SELECT COUNT(*) FROM " + table
                + " WHERE task_id = ? AND agent_id IS NOT NULL AND agent_id <> ?", Long.class, task.id, task.agentId);
        return new Section(source, List.copyOf(found.subList(0, Math.min(found.size(), LIMIT))), found.size() > LIMIT,
                conflicts == null ? 0 : conflicts);
    }
}
