package com.eap.platform.optimization;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.List;

public final class OptimizationDtos {
    private OptimizationDtos() {}
    public record Sample(@NotBlank @Pattern(regexp="[A-Za-z0-9_.:-]{1,64}") String id,
                         @NotBlank @Pattern(regexp="[A-Za-z0-9_.:-]{1,64}") String groupId,
                         @NotBlank @Pattern(regexp="TRAIN|VALIDATION|HOLDOUT") String split,
                         @NotBlank @Size(max=2000) String input,
                         @NotBlank @Size(max=2000) String expected) {}
    public record Submit(@NotNull @Positive Long agentId,@NotBlank @Size(max=128) String instanceKey,
                         @NotBlank @Pattern(regexp="[a-f0-9]{64}") String profileDigest,
                         @NotBlank @Size(max=8000) String manualPrompt,
                         @NotBlank @Pattern(regexp="GEPA|SIMPLE") String engine,
                         @NotNull @Min(1) @Max(256) Integer maxTokens,
                         @Min(2) @Max(16) int maxMetricCalls,@Min(1) @Max(64) int maxCalls,
                         @Min(1) @Max(65536) long maxOutputTokens,
                         @NotNull @Size(min=3,max=16) List<@NotNull @Valid Sample> samples,
                         @AssertTrue boolean publicSamplesConfirmed,
                         boolean reflectionContentUseConfirmed,
                         @NotBlank @Pattern(regexp="[A-Za-z0-9_.:-]{8,64}") String requestKey) {}
    public record Evaluate(@NotNull JsonNode candidate,
                           @NotBlank @Pattern(regexp="TRAIN|VALIDATION") String split,
                           @NotNull @Size(min=1,max=3) List<@NotBlank @Pattern(regexp="[A-Za-z0-9_.:-]{1,64}") String> sampleIds,
                           @NotBlank @Pattern(regexp="[a-f0-9]{64}") String requestKey) {}
    public record Reflect(@Min(0) @Max(1) int ordinal,@NotNull JsonNode candidate,
                          @NotNull @Size(min=1,max=3) List<@NotNull JsonNode> feedback) {}
    public record SearchReceipt(@NotBlank @Pattern(regexp="COMPLETED|UNKNOWN|FAILED") String status,
                                JsonNode candidate,String candidateDigest,
                                @NotBlank String engineVersion,String reasonCode) {}
    public record Claim(long id,String token) {}
    public record Evaluation(Long experimentId,String operationKey,String candidateDigest,String split,
                             String source,int sampleCount,String status,Metrics baseline,Metrics candidate) {}
    public record Metrics(int samples,int passed,int succeeded,int unknown,int usageKnown,
                          Long inputTokens,Long outputTokens,Long latencyMs,Long endToEndMs) {}
    public record HoldoutArm(String source,int registeredSamples,int independentGroups,int plannedBatches,
                             int completedBatches,Metrics baseline,Metrics candidate,
                             int baselineFailed,int candidateFailed,int baselineNotRun,int candidateNotRun) {}
    public record PhaseCost(String phase,int admittedCalls,int usageKnownCalls,Long inputTokens,
                            Long outputTokens,Long latencyP50Ms,Long latencyP95Ms,int latencySamples) {}
    public record Cost(int plannedUpperCalls,long plannedUpperOutputTokens,int admittedCalls,int settledCalls,
                       int unknownCalls,int usageKnownCalls,Long inputTokens,Long outputTokens,
                       Long elapsedMs,List<PhaseCost> phases,String monetaryStatus) {}
    public record Evidence(int protocolVersion,int trainSamples,int validationSamples,int holdoutSamples,
                           int independentHoldoutGroups,List<HoldoutArm> holdout,Cost cost) {}
    public record Summary(long id,long agentId,String instanceKey,String status,String engine,String engineVersion,
                          String profileDigest,String datasetDigest,String imageRef,int deploymentVersion,
                          int maxCalls,long maxOutputTokens,int maxMetricCalls,String recommendation,String reasonCode,
                          String createdAt,String updatedAt,List<Evaluation> evaluations,
                          long reservedOutputTokens,long usedOutputTokens,long chargedCalls,int pendingReservations,Evidence evidence) {}
}
