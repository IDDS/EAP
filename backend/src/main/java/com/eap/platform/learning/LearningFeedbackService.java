package com.eap.platform.learning;
import com.eap.platform.agent.AgentRepository;
import com.eap.platform.common.BusinessException;
import com.eap.platform.operations.AgentTaskRepository;
import com.eap.platform.observability.TraceSpanObservationRepository;
import com.eap.platform.telemetry.ModelInvocationEventRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import java.util.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

@Service
public class LearningFeedbackService {
    private final LearningFeedbackRepository repository;
    private final AgentRepository agents;
    private final AgentTaskRepository tasks;
    private final TraceSpanObservationRepository traces;
    private final ModelInvocationEventRepository models;
    private final ObjectMapper mapper;
    private final com.eap.platform.agent.AgentService visibility;
    private final com.eap.platform.config.SecurityProperties security;
    public LearningFeedbackService(LearningFeedbackRepository repository,AgentRepository agents,
            AgentTaskRepository tasks,TraceSpanObservationRepository traces,
            ModelInvocationEventRepository models,ObjectMapper mapper,com.eap.platform.agent.AgentService visibility,com.eap.platform.config.SecurityProperties security) {
        this.repository=repository;this.agents=agents;this.tasks=tasks;this.traces=traces;this.models=models;this.mapper=mapper;this.visibility=visibility;this.security=security;
    }
    public record TaskEvidence(Long id,String taskType,String status) {}
    public record Evidence(List<TaskEvidence> tasks,List<String> traces) {}
    @Transactional(readOnly=true)
    public Evidence evidence(Long agentId) {
        visibility.get(agentId);
        var page=PageRequest.of(0,100);
        var ids=new LinkedHashSet<String>(traces.feedbackTraces(agentId,page));
        ids.addAll(models.feedbackTraces(agentId,page));
        return new Evidence(tasks.feedbackTasks(agentId,page).stream().map(t->new TaskEvidence(t.getId(),t.getTaskType(),t.getStatus().name())).toList(),ids.stream().filter(v->!v.isBlank()).limit(100).toList());
    }
    @Transactional(readOnly=true)
    public List<LearningFeedbackDtos.FeedbackResponse> list(Long agentId,int limit,boolean content) {
        return repository.recent(agentId,security.isDevelopmentMode(),PageRequest.of(0,Math.max(1,Math.min(100,limit)))).stream()
            .map(f->LearningFeedbackDtos.from(f,content)).toList();
    }
    @Transactional
    public LearningFeedbackDtos.FeedbackResponse create(LearningFeedbackDtos.FeedbackRequest r,String actor,boolean content) {
        if (!Set.of("QUALITY","LATENCY","COST","TOOL","KNOWLEDGE","SAFETY").contains(r.feedbackType()))
            throw new BusinessException("反馈类型无效");
        String summary=r.summary().trim(),action=clean(r.improvementAction()),trace=clean(r.traceId());
        if (summary.isEmpty()) throw new BusinessException("请填写问题摘要");
        visibility.get(r.agentId());
        agents.lockForFeedback(r.agentId()).orElseThrow(()->new BusinessException("智能体不存在"));
        String fingerprint=digest(List.of(r.agentId(),r.feedbackType(),r.rating(),summary,
                action==null?"":action,r.taskId()==null?"":r.taskId(),trace==null?"":trace,actor));
        var existing=repository.findByAgentIdAndSubmissionKey(r.agentId(),r.requestKey());
        if (existing.isPresent()) {
            if (!fingerprint.equals(existing.get().requestFingerprint))
                throw new com.eap.platform.common.ConflictException("提交标识已用于不同反馈，请核对原记录");
            return LearningFeedbackDtos.from(existing.get(),content);
        }
        if (r.taskId()!=null && tasks.findById(r.taskId()).filter(t->r.agentId().equals(t.getAgentId())).isEmpty())
            throw new BusinessException("任务不存在或不属于该智能体");
        if (trace!=null && !traces.existsByTraceIdAndAgentId(trace,r.agentId()) && !models.existsByTraceIdAndAgentId(trace,r.agentId()))
            throw new BusinessException("Trace 不存在或未归属该智能体");
        LearningFeedback f=new LearningFeedback();
        f.agentId=r.agentId();f.feedbackType=r.feedbackType();f.rating=r.rating();f.summary=summary;
        f.improvementAction=action;f.taskId=r.taskId();f.traceId=trace;f.requestedBy=actor;
        f.submissionKey=r.requestKey();f.requestFingerprint=fingerprint;
        return LearningFeedbackDtos.from(repository.saveAndFlush(f),content);
    }
    @Transactional
    public LearningFeedbackDtos.FeedbackResponse updateStatus(Long id,LearningFeedbackDtos.StatusRequest r,String actor,boolean content) {
        var f=repository.lock(id).orElseThrow(()->new BusinessException("学习反馈不存在"));
        visibility.get(f.agentId);
        if (f.status==r.status()) return LearningFeedbackDtos.from(f,content);
        if (f.status!=r.expectedStatus()) throw new com.eap.platform.common.ConflictException("反馈状态已改变，请刷新后评审");
        boolean allowed=f.status==LearningFeedbackStatus.OPEN && Set.of(LearningFeedbackStatus.REVIEWING,LearningFeedbackStatus.REJECTED).contains(r.status())
            || f.status==LearningFeedbackStatus.REVIEWING && Set.of(LearningFeedbackStatus.ADOPTED,LearningFeedbackStatus.REJECTED).contains(r.status());
        if (!allowed) throw new BusinessException("当前状态不允许该评审动作");
        f.status=r.status();f.reviewedBy=actor;
        return LearningFeedbackDtos.from(repository.saveAndFlush(f),content);
    }
    private static String clean(String value) { return value==null||value.isBlank()?null:value.trim(); }
    private String digest(Object value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(mapper.writeValueAsString(value).getBytes(StandardCharsets.UTF_8))); }
        catch (Exception e) { throw new IllegalStateException("无法计算反馈摘要"); }
    }
}
