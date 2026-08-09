package com.eap.platform.learning;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;

public final class LearningFeedbackDtos {
    private LearningFeedbackDtos() {
    }

    public record FeedbackRequest(
            @NotNull Long agentId,
            @NotBlank @Size(max = 64) String feedbackType,
            @NotNull @Min(1) @Max(5) Integer rating,
            @NotBlank @Size(max = 1024) String summary,
            @Size(max = 1024) String improvementAction
    ) {
    }

    public record StatusRequest(@NotNull LearningFeedbackStatus status) {
    }

    public record FeedbackResponse(
            Long id,
            Long agentId,
            String feedbackType,
            Integer rating,
            String summary,
            String improvementAction,
            LearningFeedbackStatus status,
            Instant createdAt,
            Instant updatedAt
    ) {
    }

    static FeedbackResponse from(LearningFeedback feedback) {
        return new FeedbackResponse(
                feedback.id,
                feedback.agentId,
                feedback.feedbackType,
                feedback.rating,
                feedback.summary,
                feedback.improvementAction,
                feedback.status,
                feedback.createdAt,
                feedback.updatedAt
        );
    }
}

