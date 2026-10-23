package com.eap.platform.governance;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;

import java.time.Instant;

public final class GovernancePolicyDtos {
    private GovernancePolicyDtos() {
    }

    public record PolicyRequest(
            @NotBlank @Size(max = 128) String name,
            @NotNull PolicyType policyType,
            @NotBlank @Size(max = 32) String scopeType,
            @Size(max = 128) String scopeValue,
            @NotBlank @Size(max = 16384) String ruleJson,
            @NotNull EnforcementMode enforcementMode,
            boolean enabled,
            @Min(0) Long expectedRevision
    ) {
        public PolicyRequest(String name, PolicyType policyType, String scopeType, String scopeValue,
                             String ruleJson, EnforcementMode enforcementMode, boolean enabled) {
            this(name, policyType, scopeType, scopeValue, ruleJson, enforcementMode, enabled, null);
        }
    }

    public record RollbackRequest(@NotNull @Min(0) Long expectedRevision,
                                  @NotNull @Min(0) Long sourceRevision,
                                  @NotBlank @Pattern(regexp = "[a-f0-9]{64}") String sourceDigest) {}

    public record PolicyValues(String name, PolicyType policyType, String scopeType, String scopeValue,
                               String ruleJson, EnforcementMode enforcementMode, boolean enabled) {}
    public record PolicyVersion(long revision, String changeKind, Long restoredFromRevision,
                                String snapshotDigest, String principalDigest, Instant recordedAt, PolicyValues values) {}
    public record VersionHistory(long policyId, long currentRevision, boolean legacyHistoryUnavailable,
                                 boolean truncated, java.util.List<PolicyVersion> versions) {}

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
            Instant updatedAt,
            long revision
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
                policy.getUpdatedAt(),
                policy.getRevision()
        );
    }
}

