package com.eap.platform.orchestration;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AgentPlanRepository extends JpaRepository<AgentPlan, Long> {
    List<AgentPlan> findByTeamId(Long teamId);
}

