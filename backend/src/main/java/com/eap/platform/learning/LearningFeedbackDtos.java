package com.eap.platform.learning;
import jakarta.validation.constraints.*;
import java.time.Instant;
public final class LearningFeedbackDtos {
    private LearningFeedbackDtos() {}
    public record FeedbackRequest(
        @NotNull @Positive Long agentId, @NotBlank @Size(max=64) String feedbackType,
        @NotNull @Min(1) @Max(5) Integer rating, @NotBlank @Size(max=1024) String summary,
        @Size(max=1024) String improvementAction, @Positive Long taskId,
        @Size(max=128) String traceId, @NotBlank @Pattern(regexp="[A-Za-z0-9_.:-]{8,64}") String requestKey) {}
    public record StatusRequest(@NotNull LearningFeedbackStatus status, @NotNull LearningFeedbackStatus expectedStatus) {}
    public record FeedbackResponse(Long id, Long agentId, String feedbackType, Integer rating,
        String summary, String improvementAction, LearningFeedbackStatus status,
        Long taskId, String traceId, String requestedBy, String reviewedBy, boolean contentVisible,
        Instant createdAt, Instant updatedAt) {}
    static FeedbackResponse from(LearningFeedback f, boolean content) {
        return new FeedbackResponse(f.id,f.agentId,f.feedbackType,f.rating,content?f.summary:null,
            content?f.improvementAction:null,f.status,f.taskId,f.traceId,f.requestedBy,f.reviewedBy,
            content,f.createdAt,f.updatedAt);
    }
}
