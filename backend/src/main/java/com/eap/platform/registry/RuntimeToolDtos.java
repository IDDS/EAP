package com.eap.platform.registry;

import com.eap.platform.observability.ToolInvocationKind;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.*;
import java.time.Instant;

public final class RuntimeToolDtos {
    private RuntimeToolDtos(){}
    public record Check(@NotBlank @Size(max=36) String callId,
                        @NotBlank @Pattern(regexp="[A-Za-z][A-Za-z0-9_.:/-]{0,95}") String toolName,
                        @NotNull ToolInvocationKind toolKind,
                        @NotBlank @Pattern(regexp="[0-9a-fA-F]{32}") String traceId,
                        @NotNull JsonNode arguments){}
    public record Claim(@NotBlank @Size(max=36) String callId){}
    public record Receipt(@NotBlank @Size(max=36) String callId,
                          @NotBlank @Pattern(regexp="SUCCEEDED|FAILED") String outcome,
                          @NotNull @Min(0) @Max(86400000) Long durationMs,
                          @Pattern(regexp="[0-9a-f]{64}") String outputDigest){}
    public record View(String callId,String toolName,String toolKind,String traceId,String state,
                       String safetyDecision,String dataAction,String reasonCode,String decisionKey,
                       Instant createdAt,Instant expiresAt,Instant claimedAt,Instant completedAt,
                       Long durationMs,Long policyRevision,String evidenceSource,String effectId,String rootGrantId){}
    public record ClaimResult(boolean execute,View call){}
}
