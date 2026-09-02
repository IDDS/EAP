package com.eap.platform.operations;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;

public final class AgentTaskDtos {
    private AgentTaskDtos() {
    }

    public record TaskRequest(
            @NotNull Long agentId,
            Long agentVersionId,
            @NotBlank @Size(max = 64) String taskType,
            @Size(max = 1024) String inputSummary,
            @Size(max = 128) String requester
    ) {
        public TaskRequest(Long agentId, Long agentVersionId, String taskType, String inputSummary) {
            this(agentId, agentVersionId, taskType, inputSummary, null);
        }

        public TaskRequest withRequester(String value) {
            return new TaskRequest(agentId, agentVersionId, taskType, inputSummary, value);
        }
    }

    public record StatusRequest(
            @NotNull AgentTaskStatus status,
            @Size(max = 1024) String resultSummary,
            Boolean problemResolved,
            @Size(max = 1024) String resolutionSummary
    ) {
        public StatusRequest(AgentTaskStatus status, String resultSummary) {
            this(status, resultSummary, null, null);
        }
    }

    public record TaskResponse(
            Long id,
            Long agentId,
            Long agentVersionId,
            String taskType,
            String inputSummary,
            String requester,
            AgentTaskStatus status,
            Instant startedAt,
            Instant finishedAt,
            Long latencyMs,
            Long tokenCost,
            BigDecimal moneyCost,
            String resultSummary,
            Boolean problemResolved,
            String resolutionSummary,
            Instant createdAt,
            Instant updatedAt
    ) {
        public TaskResponse(
                Long id, Long agentId, Long agentVersionId, String taskType, String inputSummary,
                AgentTaskStatus status, Instant startedAt, Instant finishedAt, Long latencyMs,
                Long tokenCost, BigDecimal moneyCost, String resultSummary, Instant createdAt, Instant updatedAt
        ) {
            this(id, agentId, agentVersionId, taskType, inputSummary, null, status, startedAt, finishedAt,
                    latencyMs, tokenCost, moneyCost, resultSummary, null, null, createdAt, updatedAt);
        }
    }

    static TaskResponse from(AgentTask task) {
        return new TaskResponse(
                task.id,
                task.agentId,
                task.agentVersionId,
                task.taskType,
                task.inputSummary,
                task.requester,
                task.status,
                task.startedAt,
                task.finishedAt,
                task.latencyMs,
                task.tokenCost,
                task.moneyCost,
                task.resultSummary,
                task.problemResolved,
                task.resolutionSummary,
                task.createdAt,
                task.updatedAt
        );
    }
}
