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

    @GetMapping("/page")
    public ApiResponse<com.eap.platform.common.HistoryPage<AuditDtos.AuditLogResponse>> page(
            @jakarta.validation.Valid @org.springframework.web.bind.annotation.ModelAttribute com.eap.platform.common.HistoryQuery query) {
        String status = query.status == null || query.status.isBlank() ? null : query.status;
        if (status != null && !java.util.Set.of("SUCCESS", "FAILED").contains(status))
            throw new com.eap.platform.common.BusinessException("无效的审计结果");
        var pageable = query.pageable();
        return ApiResponse.ok(com.eap.platform.common.HistoryPage.from(auditLogRepository.findAll(
            query.specification("result", status, "actor", "action", "resourceType", "resourceId", "traceId"), pageable).map(AuditDtos::from)));
    }

    @GetMapping
    public ApiResponse<List<AuditDtos.AuditLogResponse>> list() {
        return ApiResponse.ok(auditLogRepository.findTop100ByOrderByCreatedAtDesc().stream()
                .map(AuditDtos::from)
                .toList());
    }
}
