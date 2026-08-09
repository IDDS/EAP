package com.eap.platform.registry;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AgentRuntimeInstanceRepository extends JpaRepository<AgentRuntimeInstance, Long> {
    List<AgentRuntimeInstance> findByAgentId(Long agentId);

    Optional<AgentRuntimeInstance> findFirstByAgentIdAndStatusOrderByLoadScoreAsc(Long agentId, RuntimeStatus status);

    Optional<AgentRuntimeInstance> findByInstanceKey(String instanceKey);
}
