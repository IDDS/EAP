package com.eap.platform.governance;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;

public final class GovernancePolicyDtos {
    private GovernancePolicyDtos() {
    }

    public record PolicyRequest(
            @NotBlank @Size(max = 128) String name,
            @NotNull PolicyType policyType,
            @NotBlank @Size(max = 32) String scopeType,
            @Size(max = 128) String scopeValue,
            @NotBlank String ruleJson,
            @NotNull EnforcementMode enforcementMode,
            boolean enabled
    ) {
    }

    public record PolicyResponse(
            Long id,
            String name,
            PolicyType policyType,
            String scopeType,
            String scopeValue,
            String ruleJson,
            EnforcementMode enforcementMode,
            boolean enabled,
            Instant createdAt,
            Instant updatedAt
    ) {
    }

    static PolicyResponse from(GovernancePolicy policy) {
        return new PolicyResponse(
                policy.getId(),
                policy.getName(),
                policy.getPolicyType(),
                policy.getScopeType(),
                policy.getScopeValue(),
                policy.getRuleJson(),
                policy.getEnforcementMode(),
                policy.isEnabled(),
                policy.getCreatedAt(),
                policy.getUpdatedAt()
        );
    }
}

