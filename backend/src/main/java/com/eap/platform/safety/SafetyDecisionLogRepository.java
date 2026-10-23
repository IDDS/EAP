package com.eap.platform.safety;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SafetyDecisionLogRepository extends JpaRepository<SafetyDecisionLog, Long> {
    long countByDecision(SafetyDecision decision);

    long countBySeverity(SafetySeverity severity);

    List<SafetyDecisionLog> findTop20ByOrderByCreatedAtDesc();
}

