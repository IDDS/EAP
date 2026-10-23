package com.eap.platform.execution;

import com.eap.platform.common.BusinessException;
import com.eap.platform.config.PlatformPermission;
import com.eap.platform.config.SecurityProperties;
import org.springframework.jdbc.core.SqlTypeValue;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Comparator;
import java.util.TimeZone;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;

/** Metadata-only catalogue. Reading never dispatches, reconciles or changes a receipt. */
@Service
public class ExecutionCatalogService {
    private static final int WINDOW_LIMIT = 10_000;
    private record Source(String sql, boolean utc) {}
    private final NamedParameterJdbcTemplate db;
    private final SecurityProperties security;
    public ExecutionCatalogService(NamedParameterJdbcTemplate db, SecurityProperties security) {
        this.db = db; this.security = security;
    }
    public record Entry(String kind, String runKey, Long workflowId, Long agentId, String instanceName,
                        String title, String status, String requester, Instant createdAt) {}
    public record Page(String contract, List<Entry> items, long total, int page, int size,
                       Instant observedAt, boolean ownChatsIncluded, int windowLimit) {}

    @Transactional(readOnly = true)
    public Page list(String kind, String status, String query, boolean mine, Long workflowId,
                     Instant from, Instant until, int page, int size, Authentication auth) {
        if (!Set.of("ALL", "WORKFLOW", "PLAN", "AX", "CHAT").contains(kind)
                || !Set.of("ALL", "ACTIVE", "SUCCEEDED", "ATTENTION", "UNKNOWN", "FAILED").contains(status))
            throw new BusinessException("任务类型或状态筛选无效");
        String search = query == null ? "" : query.trim();
        if (search.length() > 128 || page < 1 || page > 10000 || size < 1 || size > 50 || (long) page * size > WINDOW_LIMIT
                || (workflowId != null && workflowId <= 0) || (from != null && until != null && !from.isBefore(until)))
            throw new BusinessException("查询范围无效，请检查时间、工作流和分页参数");
        String subject = auth == null ? (security.isDevelopmentMode() ? "console" : "") : auth.getName();
        if (subject == null || subject.isBlank()) throw new BusinessException("缺少查询身份");
        boolean chats = security.isDevelopmentMode() || PlatformPermission.OPERATE_INSTANCES.allows(auth);
        Instant now = Instant.now();
        var params = new MapSqlParameterSource().addValue("now", Timestamp.from(now))
                .addValue("subject", subject).addValue("owner", hash(subject))
                .addValue("development", security.isDevelopmentMode())
                .addValue("limit", page * size);
        var sources = new ArrayList<Source>();
        sources.add(new Source("""
                select case when p.id is null then 'WORKFLOW' else 'PLAN' end as kind,
                  w.run_key, w.workflow_id, cast(null as signed) as agent_id,
                  cast(null as char(128)) as instance_name, w.objective as title,
                  w.status, w.requester, w.created_at
                from workflow_executions w left join agent_plans p
                  on p.execution_workflow_id=w.workflow_id and p.execution_run_key=w.run_key
                """, true));
        sources.add(new Source("""
                select 'AX' as kind, x.task_key as run_key, x.workflow_id, x.agent_id,
                  cast(null as char(128)) as instance_name, a.name as title,
                  x.status, x.requested_by as requester, x.created_at
                from ax_tasks x join agents a on a.id=x.agent_id
                where x.workflow_id is null and (:development=true or a.record_origin <> 'DEMO')
                """, true));
        if (chats) sources.add(new Source("""
                select 'CHAT' as kind, c.request_key as run_key, cast(null as signed) as workflow_id,
                  c.agent_id, c.instance_name, a.name as title,
                  case when c.status in ('ACCEPTED','DISPATCHED') and c.dispatch_deadline < :now
                    then 'UNKNOWN' else c.status end as status,
                  concat(:subject, '') as requester, c.created_at
                from eap_chat_submissions c join agents a on a.id=c.agent_id
                where c.owner_hash=:owner and (:development=true or a.record_origin <> 'DEMO')
                """, false));
        StringBuilder where = new StringBuilder(" where 1=1");
        if (!kind.equals("ALL")) { where.append(" and kind=:kind"); params.addValue("kind", kind); }
        if (mine) where.append(" and requester=:subject");
        if (workflowId != null) { where.append(" and workflow_id=:workflow"); params.addValue("workflow", workflowId); }
        if (from != null) where.append(" and created_at>=:fromTime");
        if (until != null) where.append(" and created_at<:untilTime");
        if (!search.isEmpty()) {
            where.append(" and (lower(title) like :search escape '!' or lower(run_key) like :search escape '!' or lower(requester) like :search escape '!')");
            params.addValue("search", "%" + search.toLowerCase(java.util.Locale.ROOT).replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%");
        }
        List<String> statuses = switch (status) {
            case "ACTIVE" -> List.of("QUEUED", "PROVISIONING", "RUNNING", "DISPATCHING", "ACCEPTED", "DISPATCHED");
            case "ATTENTION" -> List.of("UNKNOWN", "EXECUTION_UNKNOWN", "FAILED", "BLOCKED", "WAITING_APPROVAL", "NOT_DISPATCHED", "CANCELLED");
            case "UNKNOWN" -> List.of("UNKNOWN", "EXECUTION_UNKNOWN");
            case "SUCCEEDED", "FAILED" -> List.of(status);
            default -> List.of();
        };
        if (!statuses.isEmpty()) { where.append(" and status in (:statuses)"); params.addValue("statuses", statuses); }
        // Hibernate writes Instant columns in UTC; chat receipts use the original JDBC
        // connection calendar. Normalize each source before merging so ordering and
        // date boundaries never compare those two different database wall clocks.
        long total = 0;
        var candidates = new ArrayList<Entry>();
        for (Source source : sources) {
            var scoped = new MapSqlParameterSource(params.getValues());
            if (from != null) scoped.addValue("fromTime", source.utc ? utcStamp(from) : Timestamp.from(from), Types.TIMESTAMP);
            if (until != null) scoped.addValue("untilTime", source.utc ? utcStamp(until) : Timestamp.from(until), Types.TIMESTAMP);
            String relation = " from (" + source.sql + ") executions" + where;
            Long count = db.queryForObject("select count(*)" + relation, scoped, Long.class);
            total += count == null ? 0 : count;
            candidates.addAll(db.query("select *" + relation + " order by created_at desc,kind asc,run_key desc limit :limit", scoped,
                    (rs, n) -> new Entry(rs.getString("kind"), rs.getString("run_key"),
                            rs.getObject("workflow_id", Long.class), rs.getObject("agent_id", Long.class),
                            rs.getString("instance_name"), rs.getString("title"), rs.getString("status"),
                            rs.getString("requester"), (source.utc
                                    ? rs.getTimestamp("created_at", Calendar.getInstance(TimeZone.getTimeZone("UTC")))
                                    : rs.getTimestamp("created_at")).toInstant())));
        }
        candidates.sort(Comparator.comparing(Entry::createdAt).reversed()
                .thenComparing(Entry::kind).thenComparing(Entry::runKey, Comparator.reverseOrder()));
        var items = candidates.stream().skip((long) (page - 1) * size).limit(size).toList();
        return new Page("EXECUTION_CATALOG_V1", items, total, page, size, now, chats, WINDOW_LIMIT);
    }
    private static SqlTypeValue utcStamp(Instant value) {
        return (statement, index, sqlType, typeName) -> statement.setTimestamp(index, Timestamp.from(value),
                Calendar.getInstance(TimeZone.getTimeZone("UTC")));
    }
    private static String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}
