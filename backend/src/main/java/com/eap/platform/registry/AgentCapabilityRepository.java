package com.eap.platform.registry;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AgentCapabilityRepository extends JpaRepository<AgentCapability, Long> {
    List<AgentCapability> findByAgentId(Long agentId);

    List<AgentCapability> findByCapability(String capability);
}

