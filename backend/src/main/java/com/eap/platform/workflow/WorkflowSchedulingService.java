package com.eap.platform.workflow;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;

/** Equal applicant turns, with bounded aging; neither a tenant quota nor a preemptive resource scheduler. */
@Service
public class WorkflowSchedulingService {
    private final JdbcTemplate db;
    private final WorkflowExecutionRepository executions;
    private final WorkflowExecutionProperties properties;
    private final TransactionTemplate tx;
    public WorkflowSchedulingService(JdbcTemplate db, WorkflowExecutionRepository executions,
            WorkflowExecutionProperties properties, PlatformTransactionManager manager) {
        this.db=db;this.executions=executions;this.properties=properties;
        tx=new TransactionTemplate(manager);
        tx.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }
    // Called in the submission transaction, before inserting its execution. Claim uses the same lock order.
    void lockEnrollment() { db.queryForObject("select dispatch_sequence from workflow_schedule_lock where id=1 for update",Long.class); }
    void enroll(WorkflowExecution run,String principal) {
        String group=groupKey(principal);
        if (db.queryForObject("select count(*) from workflow_schedule_groups where group_key=?",Long.class,group)==0)
            db.update("insert into workflow_schedule_groups values (?,0)",group);
        db.update("insert into workflow_schedule_entries(execution_id,group_key,queued_ms) values (?,?,?)",run.id,group,run.createdAt.toEpochMilli());
    }
    static String groupKey(String requester) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(requester.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }
    public WorkflowExecution claim() { return tx.execute(ignored -> claimAt(Instant.now())); }
    WorkflowExecution claimAt(Instant now) {
        long sequence=db.queryForObject("select dispatch_sequence from workflow_schedule_lock where id=1 for update",Long.class);
        executions.expire(now.minusSeconds(properties.getTimeoutSeconds()),now);
        if(!properties.ready())return null;
        // Finite pre-upgrade queue keeps its FIFO contract; no automatic historical enrollment.
        List<Long> legacy=db.queryForList("select e.id from workflow_executions e left join workflow_schedule_entries s on s.execution_id=e.id where e.status='QUEUED' and s.execution_id is null order by e.created_at,e.id limit 1",Long.class);
        Long id;String reason=null;String group=null;
        if(!legacy.isEmpty())id=legacy.getFirst();
        else {
            long cutoff=now.toEpochMilli()-properties.getAgingSeconds()*1000L;
            var selected=db.query("select s.execution_id,s.group_key,s.queued_ms from workflow_schedule_entries s join workflow_executions e on e.id=s.execution_id join workflow_schedule_groups g on g.group_key=s.group_key where e.status='QUEUED' and s.dispatch_ms is null order by case when s.queued_ms<=? then 0 else 1 end,case when s.queued_ms<=? then s.queued_ms else g.last_dispatch_sequence end,s.queued_ms,s.execution_id limit 1",
                    (r,n)->new Candidate(r.getLong(1),r.getString(2),r.getLong(3)),cutoff,cutoff);
            if(selected.isEmpty())return null;
            var s=selected.getFirst();id=s.id;group=s.group;reason=s.queuedMs<=cutoff?"WAIT_AGING":"APPLICANT_TURN";
        }
        WorkflowExecution run=executions.lockById(id).orElseThrow();
        if(!"QUEUED".equals(run.status))throw new IllegalStateException("Queue changed during claim");
        if(group!=null) {
            long next=Math.addExact(sequence,1);
            db.update("update workflow_schedule_lock set dispatch_sequence=? where id=1",next);
            db.update("update workflow_schedule_groups set last_dispatch_sequence=? where group_key=?",next,group);
            if(db.update("update workflow_schedule_entries set dispatch_ms=?,dispatch_sequence=?,selection_reason=? where execution_id=? and dispatch_ms is null",now.toEpochMilli(),next,reason,id)!=1)
                throw new IllegalStateException("Execution already claimed");
        }
        run.status="RUNNING";run.startedAt=now;run.ownerEpoch++;run.ownerId=UUID.randomUUID().toString();
        return executions.saveAndFlush(run);
    }
    private record Candidate(long id,String group,long queuedMs) {}
    void begin(WorkflowExecution original,int node,String kind) {
        if(!enrolled(original.id))return;
        tx.executeWithoutResult(ignored->{ requireOwner(original);db.update("insert into workflow_phase_observations values (?,?,?,?,?,'RUNNING',?,null)",original.id,original.ownerEpoch,original.ownerId,node,kind,Instant.now().toEpochMilli()); });
    }
    void finish(WorkflowExecution original,int node,String kind,String state) {
        if(!enrolled(original.id))return;
        tx.executeWithoutResult(ignored->{ requireOwner(original);
            if(db.update("update workflow_phase_observations set state=?,ended_ms=? where execution_id=? and owner_epoch=? and node_sequence=? and phase_kind=? and owner_id=? and state='RUNNING'",state,Instant.now().toEpochMilli(),original.id,original.ownerEpoch,node,kind,original.ownerId)!=1)
                throw new IllegalStateException("Phase was not open"); });
    }
    // The run row fences expiry/owner replacement while a bounded reclaim RPC is issued.
    String reclaim(WorkflowExecution original, int node, java.util.function.Supplier<String> action) {
        if (!enrolled(original.id)) return "SKIPPED_LEGACY";
        return tx.execute(ignored -> {
            requireOwner(original);
            String state = action.get();
            if (db.update("update workflow_phase_observations set state=?,ended_ms=? where execution_id=? and owner_epoch=? and node_sequence=? and phase_kind='RECLAIM' and owner_id=? and state='RUNNING'",
                    state, Instant.now().toEpochMilli(), original.id, original.ownerEpoch, node, original.ownerId) != 1)
                throw new IllegalStateException("Reclaim phase was not open");
            return state;
        });
    }
    private boolean enrolled(long id) { return db.queryForObject("select count(*) from workflow_schedule_entries where execution_id=?",Long.class,id)>0; }
    private void requireOwner(WorkflowExecution original) {
        var current=executions.lockById(original.id).orElseThrow();
        if(current.recoveryVersion!=1 || !"RUNNING".equals(current.status) || current.ownerEpoch!=original.ownerEpoch
                || !Objects.equals(current.ownerId,original.ownerId) || !Instant.now().isBefore(current.startedAt.plusSeconds(properties.getTimeoutSeconds())))
            throw new IllegalStateException("Execution responsibility no longer valid");
    }
    public record Group(String groupKey,long queued,Long oldestWaitMs) {}
    public record Phase(int nodeSequence,String kind,String state,long startedMs,Long durationMs) {}
    public record Run(String runKey,long workflowId,String status,String groupKey,String selectionReason,Long queueWaitMs,List<Phase> phases) {}
    public record Summary(String policy,long agingSeconds,long observedMs,long queued,long legacyQueued,Long oldestWaitMs,
            int waitSamples,Long waitP50Ms,Long waitP95Ms,List<Group> groups,List<Run> runs) {}
    @Transactional(readOnly=true)
    public Summary report() {
        long now=Instant.now().toEpochMilli();
        long queued=db.queryForObject("select count(*) from workflow_executions where status='QUEUED'",Long.class);
        long legacy=db.queryForObject("select count(*) from workflow_executions e left join workflow_schedule_entries s on e.id=s.execution_id where e.status='QUEUED' and s.execution_id is null",Long.class);
        Long oldest=db.queryForObject("select min(s.queued_ms) from workflow_schedule_entries s join workflow_executions e on e.id=s.execution_id where e.status='QUEUED'",Long.class);
        var groups=db.query("select s.group_key,count(*),min(s.queued_ms) from workflow_schedule_entries s join workflow_executions e on e.id=s.execution_id where e.status='QUEUED' group by s.group_key order by min(s.queued_ms),s.group_key limit 100",
                (r,n)->new Group(r.getString(1),r.getLong(2),elapsed(now,r.getLong(3))));
        List<Long> waits=db.query("select queued_ms,dispatch_ms from workflow_schedule_entries where dispatch_ms is not null order by dispatch_sequence desc limit 100",(r,n)->elapsed(r.getLong(2),r.getLong(1))).stream().filter(Objects::nonNull).sorted().toList();
        var runs=db.query("select e.id,e.run_key,e.workflow_id,e.status,e.owner_epoch,e.owner_id,s.group_key,s.selection_reason,s.queued_ms,s.dispatch_ms,e.started_at from workflow_schedule_entries s join workflow_executions e on e.id=s.execution_id order by s.queued_ms desc,e.id desc limit 20",(r,n)->{
            var started=r.getTimestamp(11,Calendar.getInstance(TimeZone.getTimeZone("UTC")));
            long id=r.getLong(1),epoch=r.getLong(5);String status=r.getString(4);boolean valid="RUNNING".equals(status)&&r.getString(6)!=null&&started!=null
                    &&Instant.ofEpochMilli(now).isBefore(started.toInstant().plusSeconds(properties.getTimeoutSeconds()));
            List<Phase> phases=db.query("select owner_epoch,node_sequence,phase_kind,state,started_ms,ended_ms,owner_id from workflow_phase_observations where execution_id=? order by node_sequence,started_ms",(p,k)->{
                String state=p.getString(4);boolean open="RUNNING".equals(state);
                if(open && (!valid || p.getLong(1)!=epoch || !Objects.equals(p.getString(7),r.getString(6))))state="UNCONFIRMED";
                Long end=p.getObject(6)==null?null:p.getLong(6);
                return new Phase(p.getInt(2),p.getString(3),state,p.getLong(5),end==null?null:elapsed(end,p.getLong(5)));
            },id);
            Long dispatched=r.getObject(10)==null?null:r.getLong(10);
            return new Run(r.getString(2),r.getLong(3),status,r.getString(7),r.getString(8),dispatched==null?null:elapsed(dispatched,r.getLong(9)),phases);
        });
        return new Summary("APPLICANT_TURN_WITH_AGING_V1",properties.getAgingSeconds(),now,queued,legacy,oldest==null?null:elapsed(now,oldest),waits.size(),percentile(waits,.5),percentile(waits,.95),groups,runs);
    }
    static Long elapsed(long end,long start){return end<start?null:end-start;}
    private static Long percentile(List<Long> sorted,double p){return sorted.isEmpty()?null:sorted.get((int)Math.ceil(sorted.size()*p)-1);}
}
