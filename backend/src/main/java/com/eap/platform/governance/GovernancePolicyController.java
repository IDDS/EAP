package com.eap.platform.governance;

import com.eap.platform.common.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/policies")
public class GovernancePolicyController {
    private final GovernancePolicyService service;

    public GovernancePolicyController(GovernancePolicyService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse<List<GovernancePolicyDtos.PolicyResponse>> list() {
        return ApiResponse.ok(service.list());
    }

    @PostMapping
    public ApiResponse<GovernancePolicyDtos.PolicyResponse> create(@Valid @RequestBody GovernancePolicyDtos.PolicyRequest request) {
        return ApiResponse.ok(service.create(request));
    }

    @PutMapping("/{id}")
    public ApiResponse<GovernancePolicyDtos.PolicyResponse> update(@PathVariable Long id, @Valid @RequestBody GovernancePolicyDtos.PolicyRequest request) {
        return ApiResponse.ok(service.update(id, request));
    }

    @GetMapping("/{id}/versions")
    public ApiResponse<GovernancePolicyDtos.VersionHistory> versions(@PathVariable Long id) {
        return ApiResponse.ok(service.versions(id));
    }

    @PostMapping("/{id}/rollback")
    public ApiResponse<GovernancePolicyDtos.PolicyResponse> rollback(@PathVariable Long id,
            @Valid @RequestBody GovernancePolicyDtos.RollbackRequest request) {
        return ApiResponse.ok(service.rollback(id, request));
    }
}

