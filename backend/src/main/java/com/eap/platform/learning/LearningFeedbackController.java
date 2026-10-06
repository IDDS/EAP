package com.eap.platform.learning;
import com.eap.platform.common.ApiResponse;
import com.eap.platform.config.PlatformPermission;
import com.eap.platform.config.SecurityProperties;
import jakarta.validation.Valid;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import java.util.List;
@RestController
@RequestMapping("/api/learning-feedbacks")
public class LearningFeedbackController {
    private final LearningFeedbackService service;
    private final SecurityProperties security;
    public LearningFeedbackController(LearningFeedbackService service,SecurityProperties security) {this.service=service;this.security=security;}
    private boolean content(Authentication a) {return security.isDevelopmentMode()||PlatformPermission.VIEW_CONTENT.allows(a);}
    private String actor(Authentication a) {return a==null?"console":a.getName();}
    @GetMapping
    public ApiResponse<List<LearningFeedbackDtos.FeedbackResponse>> list(@RequestParam(required=false) Long agentId,
            @RequestParam(defaultValue="50") int limit,Authentication a) {return ApiResponse.ok(service.list(agentId,limit,content(a)));}
    @GetMapping("/evidence")
    public ApiResponse<LearningFeedbackService.Evidence> evidence(@RequestParam Long agentId) {return ApiResponse.ok(service.evidence(agentId));}
    @PostMapping
    public ApiResponse<LearningFeedbackDtos.FeedbackResponse> create(@Valid @RequestBody LearningFeedbackDtos.FeedbackRequest r,Authentication a) {
        return ApiResponse.ok(service.create(r,actor(a),content(a)));
    }
    @PostMapping("/{id}/status")
    public ApiResponse<LearningFeedbackDtos.FeedbackResponse> updateStatus(@PathVariable Long id,
            @Valid @RequestBody LearningFeedbackDtos.StatusRequest r,Authentication a) {
        return ApiResponse.ok(service.updateStatus(id,r,actor(a),content(a)));
    }
}
