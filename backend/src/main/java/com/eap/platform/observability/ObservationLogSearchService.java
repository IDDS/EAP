package com.eap.platform.observability;

import com.eap.platform.common.BusinessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;

/** Searches bounded, committed platform event text. Never collects stdout or caller content. */
@Service
public class ObservationLogSearchService {
    static final int CANDIDATE_LIMIT = 10000;
    private static final Set<String> ENTRIES = Set.of("AX_ECHO", "AX_FAIL", "AX_LLM", "EXPERIMENT", "WORKFLOW_CONTROL", "RUNTIME_TASK", "CHAT");
    private static final Set<String> EVENTS = Set.of("REGISTERED", "IDENTITY_FROZEN", "DISPATCH_COMMITTED", "RECEIPT_CONFIRMED", "CONTROL_CHECKED");
    private static final Set<String> REASONS = Set.of("ORIGINAL_INTENT_FROZEN", "ORIGINAL_CONTROL_ROUTE_FROZEN", "ORIGINAL_CREATE_RECEIPT_MATCH",
            "ORIGINAL_DISPATCH_ONLY", "ORIGINAL_TRACE_MATCH", "RECEIPT_TRACE_UNCONFIRMED", "PLATFORM_RECEIPT_RECORDED", "PLATFORM_CHECK_PASSED", "OUTCOME_UNCONFIRMED");
    private final JdbcTemplate db;
    private final ObservationCoverageService coverage;
    public ObservationLogSearchService(JdbcTemplate db, ObservationCoverageService coverage) { this.db = db; this.coverage = coverage; }
    public record Log(long operationId, String entryKind, String traceId, String eventKind, String reasonCode, Long ownerEpoch, long createdMs, String text) {}
    public record Result(String sourceContract, int windowMinutes, long windowStartMs, long observedMs, int limit,
            int candidateLimit, int candidatesScanned, boolean scopeTruncated, boolean matchesTruncated, List<Log> logs) {}

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ, timeout = 5)
    public Result search(String query, int minutes, int limit) {
        if (minutes < 1 || minutes > 10080 || limit < 1 || limit > 100) throw new BusinessException("日志窗口或条数超出范围");
        if (query == null || query.length() > 128 || !query.matches("[A-Za-z0-9_.:=/\\- ]*")) throw new BusinessException("日志检索仅支持固定元数据字符，最多128字符和4个词");
        var terms = Arrays.stream(query.trim().toLowerCase(Locale.ROOT).split(" +")).filter(s -> !s.isEmpty()).toList();
        if (terms.size() > 4 || terms.stream().anyMatch(s -> s.length() > 64)) throw new BusinessException("日志检索仅支持固定元数据字符，最多128字符和4个词");
        long now = Instant.now().toEpochMilli(), start = now - minutes * 60000L;
        // Filter visibility before the candidate cap. Stable ordering includes the complete primary key.
        var rows = db.query("select o.id,o.entry_kind,o.trace_id,e.event_kind,e.reason_code,e.owner_epoch,e.created_at "
                + "from observation_operation_events e join observation_operations o on o.id=e.operation_id left join agents a on a.id=o.agent_id "
                + "where " + coverage.visibility() + " and e.created_at>=? and e.created_at<=? "
                + "order by e.created_at desc,e.operation_id desc,e.event_kind desc limit ?", (r, n) -> {
            String entry = known(r.getString(2), ENTRIES, "UNRECOGNIZED_ENTRY"), event = known(r.getString(4), EVENTS, "UNRECOGNIZED_EVENT"),
                    reason = known(r.getString(5), REASONS, "UNRECOGNIZED_REASON"), trace = r.getString(3);
            if (trace == null || !trace.matches("[a-fA-F0-9]{32}")) trace = null;
            Long epoch = (Long)r.getObject(6); long id = r.getLong(1);
            String text = "observation operation_id=" + id + " entry=" + entry + " trace=" + (trace == null ? "UNCONFIRMED" : trace)
                    + " event=" + event + " reason=" + reason + " owner_epoch=" + (epoch == null ? "UNCONFIRMED" : epoch);
            return new Log(id, entry, trace, event, reason, epoch, r.getTimestamp(7).toInstant().toEpochMilli(), text);
        }, new Timestamp(start), new Timestamp(now), CANDIDATE_LIMIT + 1);
        var candidates = rows.stream().limit(CANDIDATE_LIMIT).toList();
        // Literal matching in memory keeps search text out of database statements and treats '_' as a literal.
        var matches = candidates.stream().filter(row -> { String text = row.text().toLowerCase(Locale.ROOT); return terms.stream().allMatch(text::contains); }).limit(limit + 1L).toList();
        return new Result("PERSISTED_OPERATION_EVENT_TEXT_V1", minutes, start, now, limit, CANDIDATE_LIMIT, candidates.size(),
                rows.size() > CANDIDATE_LIMIT, matches.size() > limit, matches.stream().limit(limit).toList());
    }
    private static String known(String value, Set<String> values, String unknown) { return values.contains(Objects.toString(value, "")) ? value : unknown; }
}
