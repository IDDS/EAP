package com.eap.platform.deployment;

import com.eap.platform.common.BusinessException;
import com.eap.platform.common.ConflictException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/** One durable admission per authenticated subject/key. No network calls inside a transaction.
 * Only the INSERT winner may dispatch; neither POST replay nor GET can resume an abandoned intent.
 * Receipts retain metadata only, so replay cannot bypass observation content policy.
 */
@Service
public class ChatSubmissionService {
    private final JdbcTemplate db;
    private final TransactionTemplate tx;
    private final SubstrateDeploymentService deployments;
    private final ObjectMapper mapper;

    public record Receipt(String requestKey, String status, String traceId, String resultDigest,
                          Integer turns, String reply, String notice) {}
    private record Row(Long agentId, String instance, String digest, String status, String trace,
                       String result, Integer turns, Instant deadline) {}

    public ChatSubmissionService(JdbcTemplate db, PlatformTransactionManager manager,
                                 SubstrateDeploymentService deployments, ObjectMapper mapper) {
        this.db = db; this.deployments = deployments; this.mapper = mapper;
        tx = new TransactionTemplate(manager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public Receipt submit(Long agentId, String instance, SubstrateDeploymentDtos.ChatRequest request, String subject) {
        String owner = owner(subject), key = key(request.requestKey());
        validateTarget(agentId, instance);
        if (request.message() == null || request.message().isBlank() || request.message().length() > 4000)
            throw new BusinessException("消息必须为1–4000个字符");
        var history = SubstrateDeploymentService.validateChatHistory(request.history());
        String digest;
        try { digest = sha(mapper.writeValueAsString(List.of(agentId, instance, request.message(), history))); }
        catch (Exception e) { throw new BusinessException("对话载荷无法编码"); }
        boolean inserted;
        try {
            tx.executeWithoutResult(s -> {
                Instant now = Instant.now();
                db.update("insert into eap_chat_submissions (owner_hash,request_key,agent_id,instance_name,payload_digest,status,created_at,dispatch_deadline,updated_at) values (?,?,?,?,?,'ACCEPTED',?,?,?)",
                        owner, key, agentId, instance, digest, stamp(now), stamp(now.plusSeconds(300)), stamp(now));
            });
            inserted = true;
        } catch (DuplicateKeyException duplicate) { inserted = false; }
        if (!inserted) {
            Row row = require(owner, key, agentId, instance);
            if (!row.digest.equals(digest)) throw new ConflictException("同一提交编号的内容已改变，请查询原回执");
            return view(key, row, null);
        }
        // Everything after admission is fail-closed. A crash may leave UNKNOWN, never a replayable key.
        SubstrateDeploymentDtos.ChatResponse result;
        try {
            result = deployments.chatOnce(agentId, instance, request.message(), history, trace -> {
                int changed = tx.execute(s -> db.update("update eap_chat_submissions set status='DISPATCHED',trace_id=?,updated_at=? where owner_hash=? and request_key=? and status='ACCEPTED' and dispatch_deadline>?",
                        trace, stamp(Instant.now()), owner, key, stamp(Instant.now())));
                if (changed != 1) throw new BusinessException("原提交已超过派发期限，未调用模型");
            });
        } catch (Exception failure) {
            // Never persist exception messages: transports/providers may echo bodies or credentials.
            tx.executeWithoutResult(s -> db.update("update eap_chat_submissions set status=case when status='ACCEPTED' then 'NOT_DISPATCHED' else 'UNKNOWN' end,updated_at=? where owner_hash=? and request_key=? and status in ('ACCEPTED','DISPATCHED')",
                    stamp(Instant.now()), owner, key));
            return view(key, require(owner, key, agentId, instance), null);
        }
        // Commit the outcome BEFORE returning the HTTP reply. Failure to commit must not permit retry.
        tx.executeWithoutResult(s -> {
            int changed = db.update("update eap_chat_submissions set status='SUCCEEDED',result_digest=?,turns=?,updated_at=? where owner_hash=? and request_key=? and status='DISPATCHED'",
                    sha(result.reply()), result.turns(), stamp(Instant.now()), owner, key);
            if (changed != 1) throw new IllegalStateException("Chat receipt transition rejected");
        });
        return view(key, require(owner, key, agentId, instance), result.reply());
    }

    /** Pure read: no actor wake-up, re-dispatch, credentials, budget changes, or deadline extension. */
    public Receipt get(Long agentId, String instance, String requestKey, String subject) {
        validateTarget(agentId, instance);
        String key = key(requestKey);
        return view(key, require(owner(subject), key, agentId, instance), null);
    }

    private Row require(String owner, String key, Long agent, String instance) {
        var rows = db.query("select * from eap_chat_submissions where owner_hash=? and request_key=?",
                (rs, n) -> new Row(rs.getLong("agent_id"), rs.getString("instance_name"), rs.getString("payload_digest"),
                        rs.getString("status"), rs.getString("trace_id"), rs.getString("result_digest"),
                        (Integer) rs.getObject("turns"), rs.getTimestamp("dispatch_deadline").toInstant()), owner, key);
        if (rows.isEmpty() || !Objects.equals(rows.getFirst().agentId, agent) || !rows.getFirst().instance.equals(instance))
            throw new BusinessException("未找到当前身份和实例的原回执；请保留编号继续核对，不要重发");
        return rows.getFirst();
    }

    private Receipt view(String key, Row row, String reply) {
        String status = row.status;
        if (row.deadline.isBefore(Instant.now())) {
            // A read cannot prove a concurrent admission transaction will not commit.
            if (status.equals("ACCEPTED")) status = "UNKNOWN";
            if (status.equals("DISPATCHED")) status = "UNKNOWN";
        }
        String notice = switch (status) {
            case "SUCCEEDED" -> reply != null ? "已完成" : "原请求已完成。回执不保存对话正文，请开始新对话，勿重发原问题。";
            case "NOT_DISPATCHED" -> "请求未派发到模型，可开始新请求。";
            case "UNKNOWN" -> "请求可能已执行，只能查询原回执和观测记录，请勿重发。";
            default -> "原请求正在处理中，请查询原回执。";
        };
        return new Receipt(key, status, row.trace, row.result, row.turns, reply, notice);
    }

    private static String owner(String subject) {
        if (subject == null || subject.isBlank()) throw new BusinessException("缺少提交身份");
        return sha(subject);
    }
    private static String key(String key) {
        if (key == null || !key.matches("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}"))
            throw new BusinessException("requestKey 必须是小写 UUID；请保留原提交编号");
        return key;
    }
    private static void validateTarget(Long agent, String instance) {
        if (agent == null || agent <= 0 || instance == null || !instance.matches("[a-z0-9][a-z0-9-]{0,127}"))
            throw new BusinessException("实例标识无效");
    }
    private static Timestamp stamp(Instant value) { return Timestamp.from(value); }
    private static String sha(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}
