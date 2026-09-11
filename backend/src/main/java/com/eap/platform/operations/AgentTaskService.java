package com.eap.platform.operations;

import com.eap.platform.agent.AgentRepository;
import com.eap.platform.common.BusinessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

@Service
public class AgentTaskService {
    private final AgentTaskRepository taskRepository;
    private final AgentRepository agentRepository;

    public AgentTaskService(AgentTaskRepository taskRepository, AgentRepository agentRepository) {
        this.taskRepository = taskRepository;
        this.agentRepository = agentRepository;
    }

    @Transactional(readOnly = true)
    public com.eap.platform.common.HistoryPage<AgentTaskDtos.TaskResponse> page(com.eap.platform.common.HistoryQuery query) {
        AgentTaskStatus status = null;
        if(query.status != null && !query.status.isBlank()) {
            try { status = AgentTaskStatus.valueOf(query.status); }
            catch(IllegalArgumentException ex) { throw new BusinessException("无效的任务状态"); }
        }
        var pageable = query.pageable();
        return com.eap.platform.common.HistoryPage.from(taskRepository.findAll(
            query.specification("status", status, "taskType", "requester", "inputSummary", "resultSummary"), pageable).map(AgentTaskDtos::from));
    }

    @Transactional(readOnly = true)
    public List<AgentTaskDtos.TaskResponse> list() {
        return taskRepository.findAll().stream().map(AgentTaskDtos::from).toList();
    }

    @Transactional
    public AgentTaskDtos.TaskResponse create(AgentTaskDtos.TaskRequest request) {
        if (!agentRepository.existsById(request.agentId())) {
            throw new BusinessException("智能体不存在");
        }
        AgentTask task = new AgentTask();
        task.agentId = request.agentId();
        task.agentVersionId = request.agentVersionId();
        task.taskType = request.taskType();
        task.inputSummary = request.inputSummary();
        task.requester = request.requester() == null || request.requester().isBlank() ? null : request.requester().trim();
        task.status = AgentTaskStatus.PENDING;
        return AgentTaskDtos.from(taskRepository.save(task));
    }

    @Transactional
    public AgentTaskDtos.TaskResponse updateStatus(Long id, AgentTaskDtos.StatusRequest request) {
        AgentTask task = taskRepository.findByIdForUpdate(id).orElseThrow(() -> new BusinessException("运行任务不存在"));
        AgentTaskStatus next = request.status();
        if (next == null) throw new BusinessException("任务状态不能为空");
        if (task.status != next) {
            if (isTerminal(task.status) || next == AgentTaskStatus.PENDING) {
                throw new BusinessException("不允许任务状态从 " + task.status + " 变更为 " + next);
            }
            task.status = next;
            if (next == AgentTaskStatus.RUNNING && task.startedAt == null) {
                task.startedAt = Instant.now();
            }
            if (isTerminal(next)) {
                task.finishedAt = Instant.now();
                if (task.startedAt == null) task.startedAt = task.createdAt;
                task.latencyMs = Math.max(task.latencyMs == null ? 0L : task.latencyMs,
                        Duration.between(task.startedAt, task.finishedAt).toMillis());
            }
        }
        if (request.resultSummary() != null) task.resultSummary = request.resultSummary();
        if (request.problemResolved() != null) {
            task.problemResolved = request.problemResolved();
        }
        if (request.resolutionSummary() != null) {
            task.resolutionSummary = request.resolutionSummary().isBlank() ? null : request.resolutionSummary().trim();
        }
        return AgentTaskDtos.from(task);
    }

    private boolean isTerminal(AgentTaskStatus status) {
        return status == AgentTaskStatus.SUCCEEDED || status == AgentTaskStatus.FAILED || status == AgentTaskStatus.CANCELLED;
    }

    @Transactional
    public void recordTelemetryUsage(Long taskId, long tokenCost, BigDecimal moneyCost, long latencyMs) {
        if (taskId == null) {
            return;
        }
        AgentTask task = taskRepository.findByIdForUpdate(taskId).orElseThrow(() -> new BusinessException("运行任务不存在"));
        task.tokenCost = (task.tokenCost == null ? 0L : task.tokenCost) + Math.max(0L, tokenCost);
        task.moneyCost = (task.moneyCost == null ? BigDecimal.ZERO : task.moneyCost)
                .add(moneyCost == null ? BigDecimal.ZERO : moneyCost);
        task.latencyMs = Math.max(task.latencyMs == null ? 0L : task.latencyMs, Math.max(0L, latencyMs));
    }
}
