package com.eap.platform.safety;

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
@RequestMapping("/api/safety")
public class GenseeSafetyController {
    private final GenseeSafetyService service;

    public GenseeSafetyController(GenseeSafetyService service) {
        this.service = service;
    }

    @GetMapping("/summary")
    public ApiResponse<SafetyDtos.SafetySummary> summary() {
        return ApiResponse.ok(service.summary());
    }

    @GetMapping("/sessions")
    public ApiResponse<List<SafetyDtos.SessionResponse>> listSessions() {
        return ApiResponse.ok(service.listSessions());
    }

    @PostMapping("/sessions")
    public ApiResponse<SafetyDtos.SessionResponse> createSession(@Valid @RequestBody SafetyDtos.SessionRequest request) {
        return ApiResponse.ok(service.createSession(request));
    }

    @PostMapping("/events/evaluate")
    public ApiResponse<SafetyDtos.SafetyEvaluationResponse> evaluate(@Valid @RequestBody SafetyDtos.SafetyEventRequest request) {
        return ApiResponse.ok(service.evaluate(request));
    }

    @GetMapping("/sessions/{sessionKey}/events")
    public ApiResponse<List<SafetyDtos.EventResponse>> listEvents(@PathVariable String sessionKey) {
        return ApiResponse.ok(service.listEvents(sessionKey));
    }

    @PostMapping("/artifacts")
    public ApiResponse<SafetyDtos.ArtifactResponse> registerArtifact(@Valid @RequestBody SafetyDtos.ArtifactRequest request) {
        return ApiResponse.ok(service.registerArtifact(request));
    }

    @GetMapping("/sessions/{sessionKey}/artifacts")
    public ApiResponse<List<SafetyDtos.ArtifactResponse>> listArtifacts(@PathVariable String sessionKey) {
        return ApiResponse.ok(service.listArtifacts(sessionKey));
    }
}

