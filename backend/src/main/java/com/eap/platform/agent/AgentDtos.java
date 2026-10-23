package com.eap.platform.agent;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;

public final class AgentDtos {
    private AgentDtos() {
    }

    public record AgentRequest(
            @NotBlank @Size(max = 64) String code,
            @NotBlank @Size(max = 128) String name,
            @Size(max = 1024) String description,
            @NotBlank @Size(max = 128) String ownerDepartment,
            @NotBlank @Size(max = 128) String ownerUser,
            @NotBlank @Size(max = 64) String category,
            @NotNull RiskLevel riskLevel
    ) {
    }

    public record StatusRequest(@NotNull AgentLifecycleStatus lifecycleStatus) {
    }

    public record AgentResponse(
            Long id,
            String code,
            String name,
            String description,
            String ownerDepartment,
            String ownerUser,
            String category,
            AgentLifecycleStatus lifecycleStatus,
            RiskLevel riskLevel,
            AgentRecordOrigin recordOrigin,
            Instant createdAt,
            Instant updatedAt
    ) {
    }

    static AgentResponse from(Agent agent) {
        return new AgentResponse(
                agent.getId(),
                agent.getCode(),
                agent.getName(),
                agent.getDescription(),
                agent.getOwnerDepartment(),
                agent.getOwnerUser(),
                agent.getCategory(),
                agent.getLifecycleStatus(),
                agent.getRiskLevel(),
                agent.getRecordOrigin(),
                agent.getCreatedAt(),
                agent.getUpdatedAt()
        );
    }
}
