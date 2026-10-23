package com.eap.platform.registry;

import com.eap.platform.common.BusinessException;
import com.eap.platform.substrate.SubstrateActorRef;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.function.Supplier;

/** Coordinates EAP dispatches and automatic reclamation by physical routing address, not registry alias. */
@Service
public class RuntimeWorkService {
    private final JdbcTemplate db;
    private final TransactionTemplate tx;

    public RuntimeWorkService(JdbcTemplate db, PlatformTransactionManager manager) {
        this.db = db;
        tx = new TransactionTemplate(manager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public Ticket start(String endpoint, String kind) {
        return start(endpoint, kind, false);
    }

    /** Recheck under the admission lock; a preview never reserves an actor or guarantees future reclaim. */
    public Ticket start(String endpoint, String kind, boolean requireReclaim) {
        String actor = SubstrateActorRef.parse(endpoint).routingValue();
        // Separate transaction: a duplicate insert must not roll back the subsequent admission.
        try { tx.executeWithoutResult(s -> db.update("insert into runtime_work_actors(actor_key,reclaim_state) values (?,'OPEN')", actor)); }
        catch (DuplicateKeyException alreadyExists) { /* Existing physical actor, including another registry alias. */ }
        String id = UUID.randomUUID().toString();
        tx.executeWithoutResult(s -> {
            if (!"OPEN".equals(lock(actor))) throw new BusinessException("实例回收状态未确认，未派发任务；请核对原实例或使用新实例");
            if (requireReclaim) requireReclaimable(endpoint);
            db.update("insert into runtime_work_claims(work_id,actor_key,work_kind,state,created_at,updated_at) values (?,?,?,'ACTIVE',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)", id, actor, kind);
        });
        return new Ticket(id, actor);
    }

    public record ReclaimReadiness(boolean allowed, String code, String message) {}

    /** Read-only evidence at this instant. No claim creation, TTL expiry, or UNKNOWN reconciliation. */
    public ReclaimReadiness reclaimReadiness(String endpoint) {
        String actor = SubstrateActorRef.parse(endpoint).routingValue();
        if (db.queryForObject("select count(*) from runtime_work_actors where actor_key=? and reclaim_state<>'OPEN'", Long.class, actor) > 0)
            return new ReclaimReadiness(false, "RECLAIM_UNCONFIRMED", "实例回收状态未确认，请核对原实例；未派发任务");
        if (db.queryForObject("select count(*) from runtime_work_claims where actor_key='*' and state<>'DONE'", Long.class) > 0)
            return new ReclaimReadiness(false, "GLOBAL_UNRESOLVED_WORK", "存在归属未确认的历史占用，自动休眠受阻；请核对历史记录，或关闭自动休眠并在任务后显式收尾");
        if (db.queryForObject("select count(*) from runtime_work_claims where actor_key=? and state<>'DONE'", Long.class, actor) > 0)
            return new ReclaimReadiness(false, "ACTOR_UNRESOLVED_WORK", "实例仍有执行中或结果未知的占用；请核对原任务，或关闭自动休眠并在任务后显式收尾");
        if (db.queryForObject("select count(*) from agent_runtime_instances where endpoint=? and protocol='SUBSTRATE_TASK'", Long.class, "substrate://" + actor) > 0)
            return new ReclaimReadiness(false, "AX_LIFECYCLE_OWNER", "实例由 AX 管理生命周期，不能由 Runtime 步骤自动休眠；请使用 AX 执行方式或关闭自动休眠");
        return new ReclaimReadiness(true, "READY", "当前未发现自动休眠阻塞；提交与派发时仍会复查");
    }

    public void requireReclaimable(String endpoint) {
        var readiness = reclaimReadiness(endpoint);
        if (!readiness.allowed()) throw new BusinessException("自动休眠检查未通过：" + readiness.message());
    }

    public <T> T perform(String endpoint, String kind, Supplier<T> action, Predicate<T> terminal) {
        try (Ticket ticket = start(endpoint, kind)) {
            T result = action.get();
            ticket.complete(terminal.test(result));
            return result;
        }
    }

    private String lock(String actor) {
        return db.queryForObject("select reclaim_state from runtime_work_actors where actor_key=? for update", String.class, actor);
    }

    public final class Ticket implements AutoCloseable {
        private final String id;
        private final String actor;
        private boolean completed;
        private Ticket(String id, String actor) { this.id = id; this.actor = actor; }

        /** A matched terminal receipt or proven pre-dispatch failure permits completion; UNKNOWN never does. */
        public void complete(boolean terminal) {
            tx.executeWithoutResult(s -> {
                lock(actor);
                if (db.update("update runtime_work_claims set state=?,updated_at=CURRENT_TIMESTAMP where work_id=? and actor_key=? and state='ACTIVE'", terminal ? "DONE" : "UNKNOWN", id, actor) != 1)
                    throw new IllegalStateException("Runtime work already settled");
            });
            completed = true;
        }

        /** Commit the admission barrier BEFORE the RPC. An ambiguous RPC/crash keeps future sends fenced. */
        public String reclaim(Runnable suspend) {
            String fence = UUID.randomUUID().toString();
            String skipped = tx.execute(s -> {
                if (!"OPEN".equals(lock(actor))) return "UNCONFIRMED";
                if (!"DONE".equals(db.queryForObject("select state from runtime_work_claims where work_id=? and actor_key=?", String.class, id, actor)))
                    return "SKIPPED_UNKNOWN";
                if (db.queryForObject("select count(*) from runtime_work_claims where (actor_key=? or actor_key='*') and state<>'DONE'", Long.class, actor) > 0)
                    return "SKIPPED_BUSY";
                // AX has its own lifecycle owner and must never be reclaimed by an aliased workflow runtime.
                if (db.queryForObject("select count(*) from agent_runtime_instances where endpoint=? and protocol='SUBSTRATE_TASK'", Long.class, "substrate://" + actor) > 0)
                    return "SKIPPED_BUSY";
                db.update("update runtime_work_actors set reclaim_state='RECLAIMING',reclaim_owner=? where actor_key=?", fence, actor);
                return null;
            });
            if (skipped != null) return skipped;
            String state;
            try { suspend.run(); state = "CONFIRMED"; }
            catch (RuntimeException failure) { state = "UNCONFIRMED"; }
            String result = state;
            tx.executeWithoutResult(s -> {
                lock(actor);
                if (db.update("update runtime_work_actors set reclaim_state=?,reclaim_owner=null where actor_key=? and reclaim_state='RECLAIMING' and reclaim_owner=?", "CONFIRMED".equals(result) ? "OPEN" : "RECLAIM_UNKNOWN", actor, fence) != 1)
                    throw new IllegalStateException("Runtime reclaim responsibility changed");
            });
            return result;
        }

        @Override public void close() {
            if (!completed) complete(false); // No time based cleanup: interruption is not proof of completion.
        }
    }
}
