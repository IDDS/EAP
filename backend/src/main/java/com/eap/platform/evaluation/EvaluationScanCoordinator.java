package com.eap.platform.evaluation;

import com.eap.platform.common.ConflictException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.util.UUID;

/** Short transactions; only one scanner owns the persistent, bounded sweep at a time. */
@Service
public class EvaluationScanCoordinator {
    private final EvaluationScanStateRepository repository;
    public EvaluationScanCoordinator(EvaluationScanStateRepository repository) { this.repository = repository; }
    public record Window(long cursor, long upper) { }
    public record Lease(String owner, Window tools, Window spans, Window rag) { }

    @Transactional
    public Lease acquire(long tools, long spans, long rag) {
        var s = repository.lockState().orElseThrow();
        Instant now = Instant.now();
        if (s.leaseOwner != null && s.leaseUntil != null && s.leaseUntil.isAfter(now))
            throw new ConflictException("已有观测评估正在运行，请稍后查看进度");
        // A fixed high-water mark lets older, mutable observations be revisited even under continuous ingestion.
        if (s.toolCursor >= s.toolUpper) { s.toolCursor = 0; s.toolUpper = tools; }
        if (s.spanCursor >= s.spanUpper) { s.spanCursor = 0; s.spanUpper = spans; }
        if (s.ragCursor >= s.ragUpper) { s.ragCursor = 0; s.ragUpper = rag; }
        s.leaseOwner = UUID.randomUUID().toString();
        s.leaseUntil = now.plusSeconds(60); s.updatedAt = now;
        return new Lease(s.leaseOwner, new Window(s.toolCursor, s.toolUpper),
                new Window(s.spanCursor, s.spanUpper), new Window(s.ragCursor, s.ragUpper));
    }

    @Transactional
    public void heartbeat(String owner) { owned(owner); }

    @Transactional
    public void advance(String owner, String source, long cursor) {
        var s = owned(owner);
        switch (source) {
            case "TOOLS" -> s.toolCursor = Math.max(s.toolCursor, Math.min(cursor, s.toolUpper));
            case "TRACES" -> s.spanCursor = Math.max(s.spanCursor, Math.min(cursor, s.spanUpper));
            case "RAG" -> s.ragCursor = Math.max(s.ragCursor, Math.min(cursor, s.ragUpper));
            default -> throw new IllegalArgumentException("Unknown scan source");
        }
    }

    @Transactional
    public void release(String owner) {
        var s = repository.lockState().orElseThrow();
        if (owner.equals(s.leaseOwner)) { s.leaseOwner = null; s.leaseUntil = null; s.updatedAt = Instant.now(); }
    }

    private EvaluationScanState owned(String owner) {
        var s = repository.lockState().orElseThrow();
        Instant now = Instant.now();
        if (!owner.equals(s.leaseOwner) || s.leaseUntil == null || !s.leaseUntil.isAfter(now))
            throw new ConflictException("扫描租约已过期，本次扫描已停止；已保存结果可安全复用");
        s.leaseUntil = now.plusSeconds(60); s.updatedAt = now;
        return s;
    }
}
