package com.eap.platform.workflow;

import com.eap.platform.common.BusinessException;
import com.eap.platform.common.ConflictException;
import com.eap.platform.registry.ScopedToolDtos;
import com.eap.platform.registry.ScopedToolService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.Instant;
import java.util.*;

/** Durable query outbox. It has no tool execution, budget, identity renewal or workflow resume path. */
@Service
public class WorkflowToolRecoveryService {
    public static final String CONTRACT = "workflow-tool-query-v1";
    private final JdbcTemplate db;
    private final WorkflowExecutionRepository executions;
    private final WorkflowExecutionProperties properties;
    private final ScopedToolService tools;
    private final WorkflowToolEffectFence effectFence;
    private final ObjectMapper mapper;
    private final TransactionTemplate tx;

    public WorkflowToolRecoveryService(JdbcTemplate db, WorkflowExecutionRepository executions,
            WorkflowExecutionProperties properties, ScopedToolService tools, ObjectMapper mapper,
            PlatformTransactionManager manager,WorkflowToolEffectFence effectFence) {
        this.effectFence=effectFence; this.db=db; this.executions=executions; this.properties=properties; this.tools=tools; this.mapper=mapper;
        tx=new TransactionTemplate(manager);
        tx.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }
    public record Entry(int sequence,String nodeKey,String taskKey,String rootId,String operationId,
            String holderKey,String actorUid,String contractVersion,String budgetKey,String state,String reason,
            long queryEpoch,Instant leaseUntil,Instant observedAt,ScopedToolDtos.Operation operation) {}
    public record Report(String contract,boolean enrolled,String mode,String responsibility,List<Entry> entries) {}
    record Claim(long id,long epoch,String owner,ScopedToolDtos.RecoveryBinding binding,ScopedToolDtos.Execute request) {}

    /** Same database stores the dispatch intent and query responsibility before any external request. */
    @Transactional
    public void prepare(Long runId,long epoch,String owner,int sequence,String nodeKey,String taskKey,
            String holder,ScopedToolDtos.Execute request) {
        effectFence.lockRegistration();
        var run=executions.lockById(runId).orElseThrow(()->new BusinessException("执行记录不存在"));
        if(owner==null || run.recoveryVersion!=1 || !"RUNNING".equals(run.status) || run.ownerEpoch!=epoch
                || !Objects.equals(run.ownerId,owner) || run.startedAt==null
                || !Instant.now().isBefore(run.startedAt.plusSeconds(properties.getTimeoutSeconds())))
            throw new ConflictException("原执行责任已失效，未登记新工具步骤");
        try {
            var steps=mapper.readValue(run.stepsJson,new TypeReference<List<WorkflowExecutionService.Step>>() {});
            if(sequence<0 || sequence!=steps.size()-1)throw new ConflictException("派发步骤不匹配");
            var step=steps.get(sequence);
            if(!"DISPATCHING".equals(step.status()) || !nodeKey.equals(step.nodeKey())
                    || !taskKey.equals(step.taskKey()) || !holder.equals(step.runtimeInstanceKey()))
                throw new ConflictException("原派发意图不匹配");
            var snapshot=mapper.readValue(run.snapshotJson,WorkflowExecutionService.Snapshot.class);
            var node=snapshot.nodes().stream().filter(n->nodeKey.equals(n.nodeKey())).findFirst().orElseThrow();
            var tool=WorkflowToolConfig.read(node);
            if(tool==null || !ScopedToolService.TOOL.equals(tool.path("name").asText())
                    || !request.equals(mapper.treeToValue(tool.path("arguments"),ScopedToolDtos.Execute.class)))
                throw new ConflictException("冻结工具参数不匹配");
        } catch(BusinessException | ConflictException e) { throw e; }
        catch(Exception e) { throw new BusinessException("持久步骤无法核对，未派发工具"); }
        var binding=tools.bindRecovery(holder,request);
        var old=db.queryForList("select execution_id,sequence_number,binding_json,request_json from workflow_tool_recovery where root_id=? and operation_id=?",
                binding.rootId(),request.operationId());
        if(!old.isEmpty()) {
            var row=old.getFirst();
            if(((Number)row.get("execution_id")).longValue()!=runId || ((Number)row.get("sequence_number")).intValue()!=sequence
                    || !json(binding).equals(row.get("binding_json")) || !json(request).equals(row.get("request_json")))
                throw new ConflictException("原工具操作已有持久步骤责任，不能绑定另一执行");
            return;
        }
        tools.requireUnsubmittedRecoveryIntent(binding,request);
        effectFence.bindRoot(run,binding);
        long now=System.currentTimeMillis();
        db.update("insert into workflow_tool_recovery(execution_id,sequence_number,node_key,task_key,root_id,operation_id,binding_json,request_json,state,reason,next_check_ms,created_ms) values(?,?,?,?,?,?,?,?,'PENDING','等待原操作核验；不会再次执行',?,?)",
                runId,sequence,nodeKey,taskKey,binding.rootId(),request.operationId(),json(binding),json(request),now,now);
    }

    /** Bounded independent reader; expired leases can transfer query responsibility after a restart. */
    public void processPending() {
        long now=System.currentTimeMillis();
        var ids=db.queryForList("select id from workflow_tool_recovery where (state in ('PENDING','UNKNOWN') and next_check_ms<=?) or (state='CHECKING' and lease_until_ms<=?) order by next_check_ms,id limit 8",Long.class,now,now);
        for(long id:ids) {
            Claim claim=claim(id);
            if(claim==null)continue;
            ScopedToolDtos.RecoveryEvidence evidence;
            try { evidence=tools.recoverOriginal(claim.binding(),claim.request()); }
            catch(RuntimeException e) { evidence=new ScopedToolDtos.RecoveryEvidence("UNKNOWN","核验读取失败，保留原责任和预算",null); }
            finish(claim,evidence);
        }
    }
    Claim claim(long id) {
        return tx.execute(ignored->{
            var rows=db.queryForList("select * from workflow_tool_recovery where id=? for update",id);
            if(rows.isEmpty())return null;
            var row=rows.getFirst();long now=System.currentTimeMillis();String state=(String)row.get("state");
            if(Set.of("CONFIRMED","NO_COMMIT").contains(state))return null;
            if("CHECKING".equals(state) && ((Number)row.get("lease_until_ms")).longValue()>now)return null;
            if(!"CHECKING".equals(state) && ((Number)row.get("next_check_ms")).longValue()>now)return null;
            long epoch=((Number)row.get("owner_epoch")).longValue()+1;String owner=UUID.randomUUID().toString();
            db.update("update workflow_tool_recovery set state='CHECKING',owner_epoch=?,owner_id=?,lease_until_ms=? where id=?",epoch,owner,now+30000,id);
            return new Claim(id,epoch,owner,read((String)row.get("binding_json"),ScopedToolDtos.RecoveryBinding.class),
                    read((String)row.get("request_json"),ScopedToolDtos.Execute.class));
        });
    }
    boolean finish(Claim claim,ScopedToolDtos.RecoveryEvidence evidence) {
        if(!Set.of("CONFIRMED","NO_COMMIT","UNKNOWN").contains(evidence.state()))throw new IllegalArgumentException("unsupported evidence state");
        return Boolean.TRUE.equals(tx.execute(ignored->{
            long now=System.currentTimeMillis();
            return db.update("update workflow_tool_recovery set state=?,reason=?,observed_ms=?,evidence_json=?,next_check_ms=?,owner_id=null,lease_until_ms=null where id=? and state='CHECKING' and owner_epoch=? and owner_id=? and lease_until_ms>?",
                    evidence.state(),evidence.reason(),now,json(evidence),now+10000,claim.id(),claim.epoch(),claim.owner(),now)==1;
        }));
    }
    @Transactional(readOnly=true)
    public Report report(Long workflowId,String runKey) {
        var run=executions.findByWorkflowIdAndRunKey(workflowId,runKey).orElseThrow(()->new BusinessException("执行记录不存在"));
        var rows=db.queryForList("select * from workflow_tool_recovery where execution_id=? order by sequence_number",run.id);
        List<Entry> entries=new ArrayList<>();
        for(var row:rows) {
            var binding=read((String)row.get("binding_json"),ScopedToolDtos.RecoveryBinding.class);
            var evidence=row.get("evidence_json")==null?null:read((String)row.get("evidence_json"),ScopedToolDtos.RecoveryEvidence.class);
            entries.add(new Entry(((Number)row.get("sequence_number")).intValue(),(String)row.get("node_key"),(String)row.get("task_key"),
                    binding.rootId(),(String)row.get("operation_id"),binding.holderKey(),binding.actorUid(),binding.contractVersion(),
                    "tool-root:"+binding.rootId(),(String)row.get("state"),(String)row.get("reason"),((Number)row.get("owner_epoch")).longValue(),
                    instant(row.get("lease_until_ms")),instant(row.get("observed_ms")),evidence==null?null:evidence.operation()));
        }
        return new Report(CONTRACT,run.recoveryVersion==1,"QUERY_ONLY","平台持久查询队列；不续授权、不重放、不退款、不推进后续节点",entries);
    }
    private static Instant instant(Object millis) { return millis==null?null:Instant.ofEpochMilli(((Number)millis).longValue()); }
    private String json(Object value) { try{return mapper.writeValueAsString(value);}catch(Exception e){throw new IllegalStateException("元数据无法保存");} }
    private <T>T read(String value,Class<T> type) { try{return mapper.readValue(value,type);}catch(Exception e){throw new IllegalStateException("元数据无法读取");} }
}
