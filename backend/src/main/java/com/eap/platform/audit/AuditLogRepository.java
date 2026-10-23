package com.eap.platform.audit;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AuditLogRepository extends JpaRepository<AuditLog, Long>, org.springframework.data.jpa.repository.JpaSpecificationExecutor<AuditLog> {
    List<AuditLog> findTop100ByOrderByCreatedAtDesc();
}
