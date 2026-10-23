package com.eap.platform.registry;

import com.eap.platform.common.*;
import com.eap.platform.config.UnauthorizedException;
import com.eap.platform.discovery.*;
import com.eap.platform.gateway.*;
import com.eap.platform.governance.*;
import com.eap.platform.observability.*;
import com.eap.platform.safety.*;
import com.fasterxml.jackson.databind.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;

@Service
public class RuntimeToolGuardService {
    @org.springframework.beans.factory.annotation.Autowired
    private com.eap.platform.budget.BudgetLedger budget;
    private final RuntimeToolCallRepository calls;
    private final RuntimeConnectionRepository connections;
    private final AgentRuntimeInstanceRepository runtimes;
    private final AgentDiscoveryService discovery;
    private final GenseeSafetyService safety;
    private final GovernancePreflightService governance;
    private final OpenAiCompatibleGatewayService classifier;
    private final ToolInvocationService tools;
    private final ObjectMapper json;
    private final RuntimeToolPolicyService policies;
    public RuntimeToolGuardService(RuntimeToolCallRepository calls,RuntimeConnectionRepository connections,
            AgentRuntimeInstanceRepository runtimes,AgentDiscoveryService discovery,GenseeSafetyService safety,
            GovernancePreflightService governance,OpenAiCompatibleGatewayService classifier,ToolInvocationService tools,ObjectMapper json,RuntimeToolPolicyService policies){
        this.calls=calls;this.connections=connections;this.runtimes=runtimes;this.discovery=discovery;
        this.safety=safety;this.governance=governance;this.classifier=classifier;this.tools=tools;this.json=json;this.policies=policies;
    }

    /** Serializes all guard transitions with credential changes, including first insert. */
    private RuntimeConnection lock(RuntimeConnectionService.Access access){
        var c=connections.lockForToolAccess(access.instanceKey()).orElseThrow(()->new UnauthorizedException("实例接入不可用"));
        if(c.generation==null||!c.generation.equals(access.generation())||c.verifiedAt==null||c.expiresAt==null||!c.expiresAt.isAfter(Instant.now()))
            throw new UnauthorizedException("实例凭证已失效");
        return c;
    }
    private void callId(String id){
        try{if(!UUID.fromString(id).toString().equals(id))throw new IllegalArgumentException();}
        catch(Exception e){throw new BusinessException("工具调用标识须为UUID");}
    }
    @Transactional
    public RuntimeToolDtos.View check(RuntimeConnectionService.Access access,RuntimeToolDtos.Check request){
        return checkInternal(access,request,null);
    }
    /** Only the scoped counter store calls this after verifying its typed contract, owner and grant. */
    @Transactional
    RuntimeToolDtos.View checkValidatedCounter(RuntimeConnectionService.Access access,ScopedToolDtos.Execute request,String trace){
        var arguments=json.valueToTree(request);
        var check=new RuntimeToolDtos.Check(request.operationId(),ScopedToolService.TOOL,com.eap.platform.observability.ToolInvocationKind.TOOL,trace,arguments);
        // These fields identify authorized store objects; they contain no business content.
        String metadata=json.valueToTree(Map.of("contract",ScopedToolService.CONTRACT,"delta",request.delta(),
                "identifierFields",List.of("grantId","operationId","resourceId"))).toString();
        return checkInternal(access,check,metadata);
    }
    private RuntimeToolDtos.View checkInternal(RuntimeConnectionService.Access access,RuntimeToolDtos.Check request,String validatedMetadata){
        callId(request.callId());
        if(request.arguments()==null||!request.arguments().isObject()||request.arguments().toString().getBytes(StandardCharsets.UTF_8).length>65536)
            throw new BusinessException("工具参数须为不超过64KiB的JSON对象");
        String trace=RuntimeScopedTelemetry.trace(access.instanceKey(),request.traceId());
        String payload=canonical(request.arguments(),0).toString();
        String fingerprint=hash(request.toolName()+"|"+request.toolKind()+"|"+trace+"|"+payload);
        var connection=lock(access);
        var existing=calls.findByInstanceKeyAndCallId(access.instanceKey(),request.callId()).orElse(null);
        if(existing!=null){
            sameGeneration(existing,access);
            if(!existing.fingerprint.equals(fingerprint))throw new ConflictException("同一工具调用标识的参数或目标已变化");
            return view(existing);
        }
        var policy=policies.current(access.agentId());
        String denied=policies.denial(policy,request.toolName(),request.toolKind().name());
        if(denied!=null){
            var c=base(access,request,trace,fingerprint,connection);
            c.policyRevision=policy==null?null:policy.revision;
            c.decisionKey="runtime-tool-denied-"+UUID.randomUUID();c.safetyDecision="NOT_EVALUATED";
            c.dataAction="NOT_EVALUATED";c.reasonCode=denied;c.state="BLOCKED";
            return view(calls.saveAndFlush(c));
        }
        discovery.registerEndpoint(new AgentDiscoveryDtos.EndpointRegistrationRequest(access.endpointKey(),access.instanceKey(),null,"RUNTIME","eap-runtime-connection/1"));
        String key="runtime-tool-"+UUID.randomUUID();
        safety.createSession(new SafetyDtos.SessionRequest(key,access.agentId(),"运行实例工具执行检查"));
        String inspected=validatedMetadata==null?payload:validatedMetadata;
        var safetyResult=safety.evaluateMetadata(new SafetyDtos.SafetyEventRequest(key,access.agentId(),null,
                SafetyEventType.TOOL_CALL,request.toolName(),"tool://"+request.toolName()+" "+inspected,null,inspected));
        // Ordinary checks still include every JSON key/value; callers cannot request a metadata exemption.
        var classification=classifier.classify(json.getNodeFactory().textNode(inspected),GatewayDtos.ClassificationHints.empty());
        var decision=governance.evaluate(new GovernanceDataDtos.PreflightRequest(key,key+"-input",access.agentId(),access.endpointKey(),null,trace,
                GovernanceDataDirection.TOOL_INPUT,classification.dataCategory(),classification.sensitivity(),classification.internalData(),classification.companySecret(),
                0,payload.getBytes(StandardCharsets.UTF_8).length,validatedMetadata==null?"EAP_RUNTIME_TOOL_GUARD":"EAP_VALIDATED_COUNTER_METADATA",null,null,classification.detectedTypes(),fingerprint,Instant.now()));
        var c=base(access,request,trace,fingerprint,connection);c.policyRevision=policy.revision;
        c.fingerprint=fingerprint;c.decisionKey=decision.decisionKey();c.safetyDecision=safetyResult.decision().decision().name();c.dataAction=decision.action().name();
        boolean allowed="ALLOW".equals(c.safetyDecision)&&decision.requestAllowed()
                &&Set.of(GovernanceGatewayDirective.PROCEED,GovernanceGatewayDirective.PROCEED_WITH_WARNING).contains(decision.gatewayDirective());
        c.state=allowed?"APPROVED":("DENY".equals(c.safetyDecision)||Set.of(GovernanceDataAction.BLOCK,GovernanceDataAction.REDACT).contains(decision.action()))?"BLOCKED":
                ("ASK".equals(c.safetyDecision)||decision.action()==GovernanceDataAction.APPROVAL_REQUIRED)?"REVIEW_REQUIRED":"BLOCKED";
        c.reasonCode=!"ALLOW".equals(c.safetyDecision)?safetyResult.decision().reasonCode():allowed?"POLICY_PASS":"DATA_"+decision.gatewayDirective();
        return view(calls.saveAndFlush(c));
    }
    @Transactional
    public RuntimeToolDtos.ClaimResult claim(RuntimeConnectionService.Access access,RuntimeToolDtos.Claim request){
        lock(access);var c=owned(access,request.callId());
        if(!"APPROVED".equals(c.state)||!c.expiresAt.isAfter(Instant.now()))return new RuntimeToolDtos.ClaimResult(false,view(c));
        String denied=policies.consume(c);
        if(denied!=null){c.state="BLOCKED";c.reasonCode=denied;calls.saveAndFlush(c);return new RuntimeToolDtos.ClaimResult(false,view(c));}
        budget.claimTool(c.agentId,c.instanceKey,c.callId,c.fingerprint,c.traceId);
        c.state="CLAIMED";c.claimedAt=Instant.now();calls.saveAndFlush(c);
        observe(c,ToolInvocationStatus.REQUESTED);
        return new RuntimeToolDtos.ClaimResult(true,view(c));
    }
    @Transactional
    public RuntimeToolDtos.View receipt(RuntimeConnectionService.Access access,RuntimeToolDtos.Receipt request){
        lock(access);var c=owned(access,request.callId());
        if(c.completedAt!=null){
            if(!c.state.equals(request.outcome())||!Objects.equals(c.durationMs,request.durationMs())||!Objects.equals(c.outputDigest,request.outputDigest()))
                throw new ConflictException("工具执行回执已保存，不能改写结果");
            return view(c);
        }
        if(!"CLAIMED".equals(c.state))throw new ConflictException("工具没有领取执行许可，不能上报执行成功或失败");
        c.state=request.outcome();c.durationMs=request.durationMs();c.outputDigest=request.outputDigest();c.completedAt=Instant.now();calls.saveAndFlush(c);
        observe(c,ToolInvocationStatus.valueOf(c.state));return view(c);
    }
    @Transactional(readOnly=true)
    public List<RuntimeToolDtos.View> list(String key){
        var r=runtimes.findByInstanceKey(key).orElseThrow(()->new BusinessException("运行实例未纳管"));
        if(!r.supportsMachineIdentity())throw new BusinessException("此记录页需要专属实例身份");
        return calls.findTop30ByInstanceKeyOrderByCreatedAtDescIdDesc(key).stream().map(this::view).toList();
    }
    private RuntimeToolCall base(RuntimeConnectionService.Access access,RuntimeToolDtos.Check request,String trace,String fingerprint,RuntimeConnection connection){
        var c=new RuntimeToolCall();c.instanceKey=access.instanceKey();c.callId=request.callId();c.generation=access.generation();
        c.agentId=access.agentId();c.endpointKey=access.endpointKey();c.toolName=request.toolName();c.toolKind=request.toolKind().name();c.traceId=trace;c.fingerprint=fingerprint;
        c.createdAt=Instant.now();c.expiresAt=c.createdAt.plusSeconds(60);
        if(connection.expiresAt.isBefore(c.expiresAt))c.expiresAt=connection.expiresAt;
        return c;
    }
    private RuntimeToolCall owned(RuntimeConnectionService.Access access,String id){
        callId(id);var c=calls.findByInstanceKeyAndCallId(access.instanceKey(),id).orElseThrow(()->new BusinessException("工具检查记录不存在"));
        sameGeneration(c,access);return c;
    }
    private void sameGeneration(RuntimeToolCall c,RuntimeConnectionService.Access access){
        if(!c.generation.equals(access.generation())||!c.agentId.equals(access.agentId()))throw new ConflictException("工具检查与当前实例凭证不一致，请勿重放旧工具操作");
    }
    private RuntimeToolDtos.View view(RuntimeToolCall c){
        String state="APPROVED".equals(c.state)&&!c.expiresAt.isAfter(Instant.now())?"EXPIRED":c.state;
        return new RuntimeToolDtos.View(c.callId,c.toolName,c.toolKind,c.traceId,state,c.safetyDecision,c.dataAction,c.reasonCode,c.decisionKey,c.createdAt,c.expiresAt,c.claimedAt,c.completedAt,c.durationMs,c.policyRevision,c.evidenceSource,c.effectId,c.rootGrantId);
    }
    /** Trusted tool-store entry point, absent from the runtime API; receipts cannot choose evidence source. */
    @Transactional(propagation=org.springframework.transaction.annotation.Propagation.MANDATORY)
    public void recordVerified(RuntimeConnectionService.Access access,String callId,String root,String effect,String outputDigest){
        lock(access);var c=owned(access,callId);
        if(!"APPROVED".equals(c.state))throw new ConflictException("工具检查状态不允许提交");
        c.state="SUCCEEDED";c.claimedAt=Instant.now();c.completedAt=c.claimedAt;c.durationMs=0L;c.outputDigest=outputDigest;
        c.evidenceSource="TOOL_STORE_VERIFIED";c.effectId=effect;c.rootGrantId=root;calls.saveAndFlush(c);observe(c,ToolInvocationStatus.SUCCEEDED);
    }
    private void observe(RuntimeToolCall c,ToolInvocationStatus status){
        tools.record(new ToolInvocationService.RecordCommand("runtime-guard-"+c.id,c.traceId,null,null,c.agentId,c.endpointKey,c.instanceKey,null,
                ToolInvocationSource.RUNTIME_GUARD,ToolInvocationKind.valueOf(c.toolKind),c.toolName,null,status,c.durationMs,null,null,
                "{\"evidence\":\""+c.evidenceSource+"\",\"guardCallId\":\""+c.callId+"\"}",c.completedAt==null?c.claimedAt:c.completedAt));
    }
    private JsonNode canonical(JsonNode value,int depth){
        if(depth>64)throw new BusinessException("工具参数嵌套超过64层");
        if(value.isObject()){
            var result=json.createObjectNode();var names=new TreeSet<String>();value.fieldNames().forEachRemaining(names::add);
            for(String name:names)result.set(name,canonical(value.get(name),depth+1));return result;
        }
        if(value.isArray()){var result=json.createArrayNode();value.forEach(item->result.add(canonical(item,depth+1)));return result;}
        return value;
    }
    private String hash(String value){
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}
        catch(Exception e){throw new IllegalStateException("无法计算请求摘要");}
    }
}
