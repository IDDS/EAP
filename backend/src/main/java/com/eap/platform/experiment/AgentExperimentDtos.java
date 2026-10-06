package com.eap.platform.experiment;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.time.Instant;
public final class AgentExperimentDtos {
 public record Target(@NotNull @Positive Long agentId,@NotBlank @Size(max=128) String instanceKey) {}
 public record Sample(@NotBlank @Size(max=2000) String input,@NotBlank @Size(max=2000) String expected) {}
 public record Submit(@NotNull @Positive Long agentId,@NotBlank @Size(max=128) String instanceKey,
  @NotBlank @Pattern(regexp="[a-f0-9]{64}") String profileDigest,
  @NotBlank @Size(max=8000) String candidatePrompt,@NotNull @Min(1) @Max(256) Integer maxTokens,
  @NotNull @Size(min=1,max=3) List<@NotNull @Valid Sample> samples,
  @AssertTrue boolean publicSamplesConfirmed,@NotBlank @Pattern(regexp="[A-Za-z0-9_.:-]{8,64}") String requestKey) {}
 public record Profile(JsonNode profile,String imageRef,Integer deploymentVersion,String actorUid,String template) {}
 public record Trial(Long id,String variant,Integer sampleIndex,String status,String traceId,String finishReason,
  Long inputTokens,Long outputTokens,String usageSource,Long latencyMs,Long endToEndMs,Long queueMs,Integer score,String reasonCode) {}
 public record Variant(Long id,String name,String configDigest) {}
 public record Summary(Long id,Long agentId,String instanceKey,String imageRef,Integer deploymentVersion,String status,
  String profileDigest,String datasetDigest,String evaluatorVersion,int maxTokens,String requestedBy,
  Instant createdAt,List<Variant> variants,List<Trial> trials) {}
 public record Content(JsonNode profile,JsonNode samples,List<VariantContent> variants,List<TrialContent> outputs) {}
 public record VariantContent(String name,String systemPrompt) {}
 public record TrialContent(Long trialId,String output) {}
}
