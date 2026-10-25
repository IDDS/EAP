package com.eap.platform.governance;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface GovernancePreflightDecisionRepository extends JpaRepository<GovernancePreflightDecision, Long>, org.springframework.data.jpa.repository.JpaSpecificationExecutor<GovernancePreflightDecision> {
    Optional<GovernancePreflightDecision> findByDecisionKey(String decisionKey);

    List<GovernancePreflightDecision> findTop500ByOrderByDecidedAtDesc();

    Optional<GovernancePreflightDecision> findTopByOrderByDecidedAtDesc();

    long countByGatewayDirective(GovernanceGatewayDirective gatewayDirective);

    long countByApprovalStatus(GovernanceApprovalStatus approvalStatus);

    @org.springframework.data.jpa.repository.Query("select coalesce(max(d.id), 0) from GovernancePreflightDecision d")
    long signalUpperBound();

    @org.springframework.data.jpa.repository.Query("""
            select d from GovernancePreflightDecision d
            where d.approvalStatus = :status and d.id > :cursor and d.id <= :upperBound order by d.id
            """)
    List<GovernancePreflightDecision> scanSignals(GovernanceApprovalStatus status, long cursor, long upperBound,
                                                 org.springframework.data.domain.Pageable page);
}
