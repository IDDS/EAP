package com.eap.platform.learning;

import com.eap.platform.common.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/learning-feedbacks")
public class LearningFeedbackController {
    private final LearningFeedbackService service;

    public LearningFeedbackController(LearningFeedbackService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse<List<LearningFeedbackDtos.FeedbackResponse>> list() {
        return ApiResponse.ok(service.list());
    }

    @PostMapping
    public ApiResponse<LearningFeedbackDtos.FeedbackResponse> create(@Valid @RequestBody LearningFeedbackDtos.FeedbackRequest request) {
        return ApiResponse.ok(service.create(request));
    }

    @PostMapping("/{id}/status")
    public ApiResponse<LearningFeedbackDtos.FeedbackResponse> updateStatus(@PathVariable Long id, @Valid @RequestBody LearningFeedbackDtos.StatusRequest request) {
        return ApiResponse.ok(service.updateStatus(id, request.status()));
    }
}

