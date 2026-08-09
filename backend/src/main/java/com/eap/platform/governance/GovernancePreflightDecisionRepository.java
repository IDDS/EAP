package com.eap.platform.governance;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface GovernancePreflightDecisionRepository extends JpaRepository<GovernancePreflightDecision, Long> {
    Optional<GovernancePreflightDecision> findByDecisionKey(String decisionKey);

    List<GovernancePreflightDecision> findTop500ByOrderByDecidedAtDesc();

    Optional<GovernancePreflightDecision> findTopByOrderByDecidedAtDesc();

    long countByGatewayDirective(GovernanceGatewayDirective gatewayDirective);

    long countByApprovalStatus(GovernanceApprovalStatus approvalStatus);
}
