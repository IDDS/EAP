package com.eap.platform.audit;

import com.eap.platform.common.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/audit-logs")
public class AuditController {
    private final AuditLogRepository auditLogRepository;

    public AuditController(AuditLogRepository auditLogRepository) {
        this.auditLogRepository = auditLogRepository;
    }

    @GetMapping
    public ApiResponse<List<AuditDtos.AuditLogResponse>> list() {
        return ApiResponse.ok(auditLogRepository.findTop100ByOrderByCreatedAtDesc().stream()
                .map(AuditDtos::from)
                .toList());
    }
}
