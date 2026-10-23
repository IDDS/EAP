package com.eap.platform.registry;

import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.List;

/** Only a bounded, synthetic counter tool. No arbitrary executable, URL or business payload. */
public final class ScopedToolDtos {
    private ScopedToolDtos() {}
    public record Root(@NotBlank @Pattern(regexp="[0-9a-f-]{36}") String grantId,
        @NotBlank @Size(max=128) String holderKey,
        @NotNull @Size(min=1,max=8) List<@NotBlank @Pattern(regexp="poc_[A-Za-z0-9_-]{1,60}") String> resourceSet,
        @NotNull @Size(min=1,max=8) List<@NotBlank @Size(max=128) String> recipientSet,
        @Min(1) @Max(100) long maxDelta, @Min(1) @Max(32) long claimLimit,
        @NotNull Instant expiresAt) {}
    public record Delegate(@NotBlank @Pattern(regexp="[0-9a-f-]{36}") String grantId,
        @NotBlank @Pattern(regexp="[0-9a-f-]{36}") String parentGrantId,
        @NotBlank @Size(max=128) String holderKey,
        @NotNull @Size(min=1,max=8) List<@NotBlank @Pattern(regexp="poc_[A-Za-z0-9_-]{1,60}") String> resourceSet,
        @NotNull @Size(min=1,max=8) List<@NotBlank @Size(max=128) String> recipientSet,
        @Min(1) @Max(100) long maxDelta, @Min(1) @Max(32) long claimLimit,
        @NotNull Instant expiresAt) {}
    public record Execute(@NotBlank @Pattern(regexp="[0-9a-f-]{36}") String grantId,
        @NotBlank @Pattern(regexp="[0-9a-f-]{36}") String operationId,
        @NotBlank @Pattern(regexp="poc_[A-Za-z0-9_-]{1,60}") String resourceId,
        @Min(1) @Max(100) long delta) {}
    public record Revoke(@NotBlank @Pattern(regexp="[0-9a-f-]{36}") String grantId) {}
    public record Binding(String instanceKey,long agentId,String actorUid) {}
    public record Grant(String grantId,String rootId,String parentGrantId,String holderKey,long agentId,
        long policyRevision,String contractVersion,List<String> resourceSet,List<String> recipientSet,
        long maxDelta,long claimLimit,long usedCount,int depth,String state,Instant expiresAt,Instant createdAt) {}
    public record Operation(String rootId,String operationId,String grantId,String holderKey,String resourceId,
        long delta,String state,String reasonCode,String effectId,Long counterValue,String effectSource,
        String traceId,Instant createdAt,Instant completedAt,boolean replayed) {}
    public record Counter(String resourceId,String rootId,long value,long effectCount) {}
    /** Capability facts belong to an implemented adapter, never to a runtime/MCP hint or allowlist. */
    public record Contract(String toolName,String kind,String contractVersion,String authority,
        boolean allowedByPolicy,String approvalSource,String resourceScope,String idempotencyDomain,
        String retention,String changedParameters,String query,String cancellation,String commitBarrier,
        String compensation,String recovery) {}
    public record Catalog(long policyRevision,boolean policyEnabled,Instant observedAt,List<Contract> tools) {}
    public record Inspection(String contractVersion,String budgetKey,Instant observedAt,Operation operation) {}
    /** Frozen original identity, policy and parameter hash; carries no authority to execute. */
    public record RecoveryBinding(String rootId,String grantId,String holderKey,long agentId,String actorUid,
        String generation,long policyRevision,String contractVersion,String operationFingerprint) {}
    public record RecoveryEvidence(String state,String reason,Operation operation) {}
    public record Dashboard(List<Grant> grants,List<Operation> operations,List<Counter> counters) {}
}
