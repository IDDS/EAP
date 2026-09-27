package com.eap.platform.deployment;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface SubstrateDeploymentRepository extends JpaRepository<SubstrateDeployment, Long> {
    List<SubstrateDeployment> findTop20ByAgentIdOrderByVersionDesc(Long agentId);

    Optional<SubstrateDeployment> findFirstByAgentIdOrderByVersionDesc(Long agentId);

    Optional<SubstrateDeployment> findFirstByAgentIdAndStatusOrderByVersionDesc(Long agentId, String status);

    Optional<SubstrateDeployment> findByAgentIdAndVersion(Long agentId, Integer version);

    Optional<SubstrateDeployment> findByAgentIdAndSubmissionKey(Long agentId, String submissionKey);

    Optional<SubstrateDeployment> findFirstByStatusInOrderByUpdatedAtAscIdAsc(Collection<String> statuses);
}
