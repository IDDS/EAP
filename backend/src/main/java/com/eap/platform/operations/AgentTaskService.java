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
        task.status = AgentTaskStatus.PENDING;
        return AgentTaskDtos.from(taskRepository.save(task));
    }

    @Transactional
    public AgentTaskDtos.TaskResponse updateStatus(Long id, AgentTaskDtos.StatusRequest request) {
        AgentTask task = taskRepository.findById(id).orElseThrow(() -> new BusinessException("运行任务不存在"));
        task.status = request.status();
        if (request.status() == AgentTaskStatus.RUNNING && task.startedAt == null) {
            task.startedAt = Instant.now();
        }
        if (request.status() == AgentTaskStatus.SUCCEEDED || request.status() == AgentTaskStatus.FAILED || request.status() == AgentTaskStatus.CANCELLED) {
            task.finishedAt = Instant.now();
            if (task.startedAt == null) {
                task.startedAt = task.createdAt;
            }
            task.latencyMs = Duration.between(task.startedAt, task.finishedAt).toMillis();
        }
        task.resultSummary = request.resultSummary();
        return AgentTaskDtos.from(task);
    }

    @Transactional
    public void recordTelemetryUsage(Long taskId, long tokenCost, BigDecimal moneyCost, long latencyMs) {
        if (taskId == null) {
            return;
        }
        AgentTask task = taskRepository.findById(taskId).orElseThrow(() -> new BusinessException("运行任务不存在"));
        task.tokenCost = (task.tokenCost == null ? 0L : task.tokenCost) + Math.max(0L, tokenCost);
        task.moneyCost = (task.moneyCost == null ? BigDecimal.ZERO : task.moneyCost)
                .add(moneyCost == null ? BigDecimal.ZERO : moneyCost);
        task.latencyMs = Math.max(task.latencyMs == null ? 0L : task.latencyMs, Math.max(0L, latencyMs));
    }
}
