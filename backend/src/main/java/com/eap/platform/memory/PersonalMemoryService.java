package com.eap.platform.memory;

import com.eap.platform.audit.AuditLog;
import com.eap.platform.audit.AuditLogRepository;
import com.eap.platform.common.BusinessException;
import com.eap.platform.common.ConflictException;
import com.eap.platform.common.TraceContext;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

@Service
public class PersonalMemoryService {
    private static final int LIMIT = 50;
    private final JdbcTemplate db;
    private final AuditLogRepository audit;
    public PersonalMemoryService(JdbcTemplate db, AuditLogRepository audit) { this.db = db; this.audit = audit; }

    @Transactional(readOnly = true)
    public PersonalMemoryController.Page list(Authentication authentication, String cursor) {
        String owner = owner(authentication);
        long now = System.currentTimeMillis();
        Cursor before = parseCursor(cursor);
        var items = db.query("select memory_id,content,created_ms,expires_ms from personal_memories where owner_hash=? and revoked_ms is null and (created_ms<? or (created_ms=? and memory_id<?)) order by created_ms desc,memory_id desc limit ?",
                (rs, n) -> {
                    Long expires = (Long) rs.getObject(4);
                    return new PersonalMemoryController.Memory(rs.getString(1), rs.getString(2), rs.getLong(3), expires,
                            expires != null && expires <= now ? "EXPIRED" : "ACTIVE");
                }, owner, before.created, before.created, before.id, LIMIT + 1);
        var selected = List.copyOf(items.stream().limit(LIMIT).toList());
        String next = items.size() > LIMIT ? encodeCursor(selected.getLast().createdAtMs(), selected.getLast().id()) : null;
        return new PersonalMemoryController.Page(selected, next);
    }

    @Transactional
    public PersonalMemoryController.Memory create(Authentication authentication, PersonalMemoryController.CreateRequest request) {
        String owner = owner(authentication);
        if (!Boolean.TRUE.equals(request.explicitlyRemember())) throw new BusinessException("请明确确认保存此记忆");
        String content = request.content().trim();
        if (content.isEmpty()) throw new BusinessException("记忆内容不能为空");
        var existing = db.query("select memory_id,content,created_ms,expires_ms,revoked_ms from personal_memories where owner_hash=? and request_key=?",
                (rs, n) -> new Existing(rs.getString(1), rs.getString(2), rs.getLong(3), (Long) rs.getObject(4), (Long) rs.getObject(5)), owner, request.requestKey());
        if (!existing.isEmpty()) {
            Existing original = existing.getFirst();
            if (original.revoked != null || (original.expires != null && original.expires <= System.currentTimeMillis())
                    || !content.equals(original.content) || !java.util.Objects.equals(request.expiresAtMs(), original.expires))
                throw new ConflictException("此提交编号已使用，请核对原记录");
            return new PersonalMemoryController.Memory(original.id, original.content, original.created, original.expires, "ACTIVE");
        }
        long now = System.currentTimeMillis();
        if (request.expiresAtMs() != null && request.expiresAtMs() <= now) throw new BusinessException("过期时间必须晚于当前时间");
        String id = UUID.randomUUID().toString();
        try {
            db.update("insert into personal_memories(memory_id,owner_hash,request_key,content,expires_ms,created_ms) values(?,?,?,?,?,?)",
                    id, owner, request.requestKey(), content, request.expiresAtMs(), now);
        } catch (DataIntegrityViolationException ex) {
            throw new ConflictException("提交正在处理，请用原编号刷新核对");
        }
        audit(owner, "PERSONAL_MEMORY_CREATE", id);
        return new PersonalMemoryController.Memory(id, content, now, request.expiresAtMs(), "ACTIVE");
    }

    @Transactional
    public void revoke(Authentication authentication, String id) {
        String owner = owner(authentication);
        if (id == null || !id.matches("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}"))
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        int changed = db.update("update personal_memories set content=null,revoked_ms=? where owner_hash=? and memory_id=? and revoked_ms is null", System.currentTimeMillis(), owner, id);
        if (changed == 1) { audit(owner, "PERSONAL_MEMORY_REVOKE", id); return; }
        Long count = db.queryForObject("select count(*) from personal_memories where owner_hash=? and memory_id=? and revoked_ms is not null", Long.class, owner, id);
        if (count == null || count == 0) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    }

    private void audit(String owner, String action, String id) {
        var item = new AuditLog();
        item.setActor("memory:" + owner); item.setAction(action); item.setResourceType("PERSONAL_MEMORY");
        item.setResourceId(id); item.setResult("SUCCESS"); item.setTraceId(TraceContext.traceId()); item.setCreatedAt(Instant.now());
        audit.save(item);
    }

    private static String owner(Authentication authentication) {
        if (!(authentication instanceof JwtAuthenticationToken jwt) || !authentication.isAuthenticated()) throw new AccessDeniedException("需要真实认证身份");
        String issuer = jwt.getToken().getClaimAsString("iss"), subject = jwt.getToken().getSubject();
        if (issuer == null || issuer.isBlank() || subject == null || subject.isBlank()) throw new AccessDeniedException("缺少身份主体");
        try {
            byte[] i = issuer.getBytes(StandardCharsets.UTF_8), s = subject.getBytes(StandardCharsets.UTF_8);
            var bytes = ByteBuffer.allocate(8 + i.length + s.length).putInt(i.length).put(i).putInt(s.length).put(s).array();
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (java.security.NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
    }
    private static String encodeCursor(long created, String id) {
        return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString((created + ":" + id).getBytes(StandardCharsets.US_ASCII));
    }
    private static Cursor parseCursor(String cursor) {
        if (cursor == null) return new Cursor(Long.MAX_VALUE, "~");
        try {
            if (!cursor.matches("[A-Za-z0-9_-]{1,100}")) throw new IllegalArgumentException();
            String decoded = new String(java.util.Base64.getUrlDecoder().decode(cursor), StandardCharsets.US_ASCII);
            String[] parts = decoded.split(":", -1);
            if (parts.length != 2 || !parts[1].matches("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}")) throw new IllegalArgumentException();
            long created = Long.parseLong(parts[0]);
            if (created <= 0 || !encodeCursor(created, parts[1]).equals(cursor)) throw new IllegalArgumentException();
            return new Cursor(created, parts[1]);
        } catch (IllegalArgumentException ex) { throw new BusinessException("记忆分页位置无效，请刷新"); }
    }
    private record Cursor(long created, String id) {}
    private record Existing(String id, String content, long created, Long expires, Long revoked) {}
}
