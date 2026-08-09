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
            @Size(max = 1024) String inputSummary
    ) {
    }

    public record StatusRequest(@NotNull AgentTaskStatus status, @Size(max = 1024) String resultSummary) {
    }

    public record TaskResponse(
            Long id,
            Long agentId,
            Long agentVersionId,
            String taskType,
            String inputSummary,
            AgentTaskStatus status,
            Instant startedAt,
            Instant finishedAt,
            Long latencyMs,
            Long tokenCost,
            BigDecimal moneyCost,
            String resultSummary,
            Instant createdAt,
            Instant updatedAt
    ) {
    }

    static TaskResponse from(AgentTask task) {
        return new TaskResponse(
                task.id,
                task.agentId,
                task.agentVersionId,
                task.taskType,
                task.inputSummary,
                task.status,
                task.startedAt,
                task.finishedAt,
                task.latencyMs,
                task.tokenCost,
                task.moneyCost,
                task.resultSummary,
                task.createdAt,
                task.updatedAt
        );
    }
}

