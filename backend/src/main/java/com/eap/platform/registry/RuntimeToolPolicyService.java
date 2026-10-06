package com.eap.platform.registry;

import com.eap.platform.agent.*;
import com.eap.platform.audit.*;
import com.eap.platform.common.*;
import com.eap.platform.observability.ToolInvocationKind;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.util.*;

@Service
public class RuntimeToolPolicyService {
    public record Tool(@NotBlank @Pattern(regexp="[A-Za-z][A-Za-z0-9_.:/-]{0,95}") String name,
                       @NotNull ToolInvocationKind kind) {}
    public record Update(@NotBlank @Size(max=128) String instanceKey, @Min(0) long expectedRevision,
                         boolean enabled, @NotNull @Size(max=64) List<@NotNull @Valid Tool> allowedTools,
                         @Min(0) @Max(1000000000) long claimLimit) {}
    public record View(Long agentId,long revision,boolean enabled,List<Tool> allowedTools,
                       long claimLimit,long claimedCount,long remainingClaims,Instant updatedAt) {}
    private final RuntimeToolPolicyRepository policies;
    private final AgentRuntimeInstanceRepository runtimes;
    private final AgentRepository agentRows;
    private final AgentService agents;
    private final AuditLogRepository audit;
    private final ObjectMapper json;
    public RuntimeToolPolicyService(RuntimeToolPolicyRepository policies,AgentRuntimeInstanceRepository runtimes,
            AgentRepository agentRows,AgentService agents,AuditLogRepository audit,ObjectMapper json){
        this.policies=policies;this.runtimes=runtimes;this.agentRows=agentRows;this.agents=agents;this.audit=audit;this.json=json;
    }
    private Long agent(String instanceKey){
        var r=runtimes.findByInstanceKey(instanceKey).orElseThrow(()->new BusinessException("运行实例未纳管"));
        if(!r.supportsMachineIdentity())throw new BusinessException("此授权需要专属实例身份");
        agents.get(r.agentId);return r.agentId;
    }
    @Transactional(readOnly=true)
    public View get(String instanceKey){Long id=agent(instanceKey);return view(id,policies.findById(id).orElse(null));}

    @Transactional
    public View update(Update request,String subject){
        Long id=agent(request.instanceKey());
        // Also serializes the first policy insert. All guard callers acquire connection -> agent -> policy.
        agentRows.lockForToolPolicy(id).orElseThrow(()->new BusinessException("智能体不存在"));
        var p=policies.lockByAgentId(id).orElse(null);var before=view(id,p);
        if(request.expectedRevision()!=before.revision())throw new ConflictException("工具授权已变化，请刷新后重新编辑");
        var scopes=new TreeSet<Tool>(Comparator.comparing(Tool::name).thenComparing(t->t.kind().name()));
        scopes.addAll(request.allowedTools());
        if(request.enabled()&&scopes.isEmpty())throw new BusinessException("启用授权时至少选择一个工具");
        if(request.claimLimit()<before.claimedCount())throw new BusinessException("累计额度不能低于已领取次数");
        if(p==null){p=new RuntimeToolPolicy();p.agentId=id;}
        p.revision=before.revision()+1;p.enabled=request.enabled();p.allowedToolsJson=write(scopes);
        p.claimLimit=request.claimLimit();p.updatedAt=Instant.now();policies.saveAndFlush(p);
        var after=view(id,p);var e=new AuditLog();e.setActor(subject==null?"console":subject);
        e.setAction("RUNTIME_TOOL_POLICY_UPDATED");e.setResourceType("AGENT");e.setResourceId(id.toString());
        e.setBeforeJson(write(before));e.setAfterJson(write(after));e.setResult("SUCCESS");
        e.setTraceId(TraceContext.traceId());e.setCreatedAt(p.updatedAt);audit.save(e);return after;
    }

    RuntimeToolPolicy current(Long agentId){return policies.findById(agentId).orElse(null);}
    String denial(RuntimeToolPolicy p,String name,String kind){
        if(p==null)return "TOOL_POLICY_MISSING";
        if(!p.enabled)return "TOOL_POLICY_DISABLED";
        if(tools(p).stream().noneMatch(t->t.name().equals(name)&&t.kind().name().equals(kind)))return "TOOL_SCOPE_DENIED";
        return null;
    }
    /** Must join the caller's claim transaction; unknown external effects never refund an issued grant. */
    @Transactional(propagation=org.springframework.transaction.annotation.Propagation.MANDATORY)
    public String consume(RuntimeToolCall call){
        agentRows.lockForToolPolicy(call.agentId).orElseThrow(()->new BusinessException("智能体不存在"));
        var p=policies.lockByAgentId(call.agentId).orElse(null);
        String reason=denial(p,call.toolName,call.toolKind);if(reason!=null)return reason;
        if(!Objects.equals(call.policyRevision,p.revision))return "TOOL_POLICY_CHANGED";
        if(p.claimedCount>=p.claimLimit)return "TOOL_BUDGET_EXHAUSTED";
        p.claimedCount++;policies.saveAndFlush(p);return null;
    }
    private List<Tool> tools(RuntimeToolPolicy p){
        try{return List.of(json.readValue(p.allowedToolsJson,Tool[].class));}
        catch(Exception e){throw new IllegalStateException("工具授权数据不可用");}
    }
    private View view(Long id,RuntimeToolPolicy p){
        return p==null?new View(id,0,false,List.of(),0,0,0,null):new View(id,p.revision,p.enabled,tools(p),p.claimLimit,p.claimedCount,Math.max(0,p.claimLimit-p.claimedCount),p.updatedAt);
    }
    private String write(Object value){try{return json.writeValueAsString(value);}catch(Exception e){throw new IllegalStateException("无法保存工具授权");}}
}
