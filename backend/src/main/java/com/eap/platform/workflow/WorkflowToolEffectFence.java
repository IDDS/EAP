package com.eap.platform.workflow;

import com.eap.platform.common.ConflictException;
import com.eap.platform.registry.RuntimeConnectionService;
import com.eap.platform.registry.ScopedToolDtos;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.util.Objects;

/** Effect-store fence for newly enrolled controlled-counter roots, independent of actor hints. */
@Service
@Transactional(propagation = Propagation.MANDATORY)
public class WorkflowToolEffectFence {
    private final JdbcTemplate db;
    private final WorkflowExecutionRepository executions;
    private final WorkflowExecutionProperties properties;
    private final ObjectMapper json;

    public WorkflowToolEffectFence(JdbcTemplate db,WorkflowExecutionRepository executions,
            WorkflowExecutionProperties properties,ObjectMapper json) {
        this.db=db;this.executions=executions;this.properties=properties;this.json=json;
    }

    /** All registration and effect writes use scope -> workflow -> identity/policy -> budget locks. */
    public void lockRegistration() {
        db.queryForObject("select id from scoped_tool_lock where id=1 for update",Long.class);
    }

    void bindRoot(WorkflowExecution run,ScopedToolDtos.RecoveryBinding binding) {
        var rows=db.queryForList("select * from workflow_tool_effect_roots where root_id=? for update",binding.rootId());
        if(!rows.isEmpty()) {
            var r=rows.getFirst();
            if(((Number)r.get("execution_id")).longValue()!=run.id
                    || ((Number)r.get("dispatch_epoch")).longValue()!=run.ownerEpoch
                    || !Objects.equals(r.get("dispatch_owner"),run.ownerId))
                throw new ConflictException("原工具根已绑定另一执行责任，不能换运行或轮次接管");
            return;
        }
        if(db.queryForObject("select count(*) from scoped_tool_operations where root_id=?",Long.class,binding.rootId())!=0
                || db.queryForObject("select count(*) from workflow_tool_recovery where root_id=?",Long.class,binding.rootId())!=0)
            throw new ConflictException("已有原工具意图或历史查询责任，不能升级原授权根的效果协议");
        db.update("insert into workflow_tool_effect_roots(root_id,execution_id,dispatch_epoch,dispatch_owner,created_ms) values(?,?,?,?,?)",
                binding.rootId(),run.id,run.ownerEpoch,run.ownerId,System.currentTimeMillis());
    }

    /** Null permits this transaction; a reason rejects only the enrolled original operation. */
    public String assess(String root,RuntimeConnectionService.Access access,ScopedToolDtos.Execute request) {
        var roots=db.queryForList("select * from workflow_tool_effect_roots where root_id=? for update",root);
        if(roots.isEmpty())return null; // Historical and non-workflow roots keep their original contract.
        var rootRow=roots.getFirst();
        var run=executions.lockById(((Number)rootRow.get("execution_id")).longValue()).orElseThrow(
                ()->new ConflictException("原工作流执行责任不存在，未提交工具效果"));
        var entries=db.queryForList("select * from workflow_tool_recovery where root_id=? and operation_id=?",root,request.operationId());
        if(entries.size()!=1)throw new ConflictException("工作流绑定工具根只接受已登记的原操作，不能换操作标识");
        var row=entries.getFirst();
        try {
            var binding=json.readValue((String)row.get("binding_json"),ScopedToolDtos.RecoveryBinding.class);
            var original=json.readValue((String)row.get("request_json"),ScopedToolDtos.Execute.class);
            if(!original.equals(request) || !root.equals(binding.rootId())
                    || !access.instanceKey().equals(binding.holderKey()) || access.agentId()!=binding.agentId()
                    || !access.generation().equals(binding.generation())
                    || ((Number)row.get("execution_id")).longValue()!=run.id)
                throw new ConflictException("原工具参数或实例绑定不匹配，未提交效果");
            if(run.recoveryVersion!=1 || !"RUNNING".equals(run.status)
                    || run.ownerEpoch!=((Number)rootRow.get("dispatch_epoch")).longValue()
                    || !Objects.equals(run.ownerId,rootRow.get("dispatch_owner")))return "WORKFLOW_OWNER_CHANGED";
            if(run.startedAt==null || !Instant.now().isBefore(run.startedAt.plusSeconds(properties.getTimeoutSeconds())))
                return "WORKFLOW_DEADLINE_EXPIRED";
            var steps=json.readTree(run.stepsJson);
            int sequence=((Number)row.get("sequence_number")).intValue();
            if(!steps.isArray() || sequence<0 || sequence!=steps.size()-1)return "WORKFLOW_STEP_CHANGED";
            var step=steps.get(sequence);
            if(!"DISPATCHING".equals(step.path("status").asText())
                    || !Objects.equals(row.get("node_key"),step.path("nodeKey").asText())
                    || !Objects.equals(row.get("task_key"),step.path("taskKey").asText())
                    || !binding.holderKey().equals(step.path("runtimeInstanceKey").asText()))return "WORKFLOW_STEP_CHANGED";
            return null;
        } catch(ConflictException e) { throw e; }
        catch(Exception e) { throw new ConflictException("原工具责任元数据无法核对，未提交效果"); }
    }

    /** Throwing inside the commit transaction rolls back policy/budget writes before any effect. */
    public void beforeEffect(String root,RuntimeConnectionService.Access access,ScopedToolDtos.Execute request) {
        String denied=assess(root,access,request);
        if(denied!=null)throw new ExpiredResponsibility(denied);
    }

    public static final class ExpiredResponsibility extends RuntimeException {
        public ExpiredResponsibility(String reason) { super(reason); }
    }
}
